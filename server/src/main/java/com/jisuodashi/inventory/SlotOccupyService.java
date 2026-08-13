package com.jisuodashi.inventory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jisuodashi.catalog.Pricing;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.inventory.SlotOccupyStore.BedRef;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderInsert;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import com.jisuodashi.inventory.SlotOccupyStore.DelayedJobInsert;
import com.jisuodashi.inventory.SlotOccupyStore.SlotHoldMeta;
import com.jisuodashi.inventory.SlotOccupyStore.IdemInsert;
import com.jisuodashi.inventory.SlotOccupyStore.IdemRow;
import com.jisuodashi.inventory.SlotOccupyStore.OccupancyInsert;
import com.jisuodashi.inventory.SlotOccupyStore.OrderItemInsert;
import com.jisuodashi.inventory.SlotOccupyStore.OwnedSlotRow;
import com.jisuodashi.inventory.SlotOccupyStore.ProjectRef;
import com.jisuodashi.inventory.SlotOccupyStore.SlotRow;
import com.jisuodashi.inventory.SlotOccupyStore.TherapistRef;
import com.jisuodashi.staff.TreatmentNoteRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Dual-resource occupy. lockNew is the only first-booking write path.
 * Order: Redis therapist-day → BEGIN → idempotency FOR UPDATE → snowflake
 * orderId/holdId → FREE preselect then FOR UPDATE by id → occupancy INSERT
 * → booking_order same TX.
 * <p>
 * Lock order is therapist then beds: therapist-day Redis already serializes the
 * same therapist; beds are walked by sort_no, id. Unpaid dest is LOCKED.
 * <p>
 * Law A (D25): {@link #releaseLock} / {@link #forceFreeByHold} /
 * {@link #releaseUnconsumed} / {@link #releaseAddOnHold} MUST NOT
 * {@code fire()} the order state machine. They join the caller TX.
 * {@code ReleaseLock} frees LOCKED rows when the order is PENDING_PAY or
 * CLOSED (caller already wrote the target). Never BOOKED / IN_SERVICE.
 */
@Service
public class SlotOccupyService {

    public static final String SCOPE_BOOKING = "booking";
    public static final String JOB_RELEASE_LOCK = "RELEASE_LOCK";
    public static final String JOB_RELEASE_ADDON = "RELEASE_ADDON";
    public static final String ORDER_PENDING_PAY = "PENDING_PAY";
    public static final String ORDER_CLOSED = "CLOSED";
    public static final int SCAN_BATCH = 500;
    public static final int STUCK_LOCK_MINUTES = 30;
    public static final String ITEM_PROJECT = "PROJECT";
    public static final String ITEM_ADD_ON = "ADD_ON";
    public static final String SCOPE_ADDON = "addon";
    public static final String ORDER_IN_SERVICE = "IN_SERVICE";
    public static final int LOCK_MINUTES = 15;
    public static final int IDEMPOTENT_TAKEOVER_SECONDS = 30;
    public static final int DEADLOCK_RETRIES = 3;

    private static final DateTimeFormatter ORDER_DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    private final SlotOccupyStore store;
    private final TherapistDayLock dayLock;
    private final LongSupplier ids;
    private final AppClock clock;
    private final TransactionTemplate tx;
    private final String instanceId;
    private final StringRedisTemplate redis;
    private final Counter stalePaid;
    private final AvailabilityCache availCache;

    @Autowired
    public SlotOccupyService(
            SlotOccupyStore store,
            TherapistDayLock dayLock,
            SnowflakeIdGenerator ids,
            AppClock clock,
            PlatformTransactionManager txManager,
            AppProperties properties,
            @Autowired(required = false) StringRedisTemplate redis,
            @Autowired(required = false) MeterRegistry meters,
            @Autowired(required = false) AvailabilityCache availCache
    ) {
        this(store, dayLock, ids::nextId, clock, new TransactionTemplate(txManager),
                "w" + properties.getSnowflake().getWorkerId(), redis, meters, availCache);
    }

    public SlotOccupyService(
            SlotOccupyStore store,
            TherapistDayLock dayLock,
            LongSupplier ids,
            AppClock clock
    ) {
        this(store, dayLock, ids, clock, null, "test", null, null, null);
    }

    public SlotOccupyService(
            SlotOccupyStore store,
            TherapistDayLock dayLock,
            LongSupplier ids,
            AppClock clock,
            MeterRegistry meters
    ) {
        this(store, dayLock, ids, clock, null, "test", null, meters, null);
    }

    public SlotOccupyService(
            SlotOccupyStore store,
            TherapistDayLock dayLock,
            LongSupplier ids,
            AppClock clock,
            AvailabilityCache availCache
    ) {
        this(store, dayLock, ids, clock, null, "test", null, null, availCache);
    }

    SlotOccupyService(
            SlotOccupyStore store,
            TherapistDayLock dayLock,
            LongSupplier ids,
            AppClock clock,
            TransactionTemplate tx,
            String instanceId,
            StringRedisTemplate redis,
            MeterRegistry meters,
            AvailabilityCache availCache
    ) {
        this.store = store;
        this.dayLock = dayLock;
        this.ids = ids;
        this.clock = clock;
        this.tx = tx;
        this.instanceId = instanceId;
        this.redis = redis;
        this.availCache = availCache;
        if (meters == null) {
            this.stalePaid = null;
        } else {
            this.stalePaid = Counter.builder("slot.locked.stale_paid")
                    .description("Expired LOCKED holds skipped because the order is paid/in-service or add-on")
                    .register(meters);
            Gauge.builder("slot.locked.stuck_30m", store,
                            s -> s.countLockedExpiredBefore(clock.now().minusMinutes(STUCK_LOCK_MINUTES)))
                    .description("LOCKED rows with lock_expire_at older than 30 minutes")
                    .register(meters);
        }
    }

    public LockNewResult lockNew(LockNewCommand cmd) {
        if (cmd.requestId() == null || cmd.requestId().isBlank()) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "requestId 不能为空");
        }
        String token = dayLock.tryAcquire(cmd.therapistId(), cmd.date());
        if (token == null) {
            throw new ApiException(ErrorCodes.LOCK_CONFLICT, "锁冲突，请重试");
        }
        try {
            return retryDeadlock(() -> inTx(() -> doLockNew(cmd)));
        } finally {
            dayLock.release(cmd.therapistId(), cmd.date(), token);
            invalidateAvailability(cmd.storeId(), cmd.date());
        }
    }

    /**
     * Free LOCKED slots for a hold. No {@code fire()}.
     * Missing order → orphan {@link #forceFreeByHold}.
     * PENDING_PAY or CLOSED (fire already CAS'd the target) → free LOCKED only.
     * BOOKED / IN_SERVICE → skip (paid inventory; force-release is the drill).
     */
    public ReleaseResult releaseLock(long holdId) {
        return inStoreTx(() -> doReleaseLock(holdId));
    }

    /**
     * FORCE free LOCKED slots for this hold (orphans / rollback drill).
     * Only LOCKED occupancy and LOCKED rows. Does not {@code fire()}.
     * Locks the order row when present so this cannot race {@link #confirmPaidSlots}.
     */
    public ReleaseResult forceFreeByHold(long holdId) {
        return inStoreTx(() -> doForceFreeByHold(holdId));
    }

    /**
     * PAY_SUCCESS sibling: promote LOCKED service slots to BOOKED, last B to
     * BUFFER, clear {@code lock_expire_at}, mark {@code RELEASE_LOCK} DONE.
     */
    public ConfirmPaidResult confirmPaidSlots(long orderId) {
        return inStoreTx(() -> doConfirmPaidSlots(orderId));
    }

    /**
     * Refund / no-show / abort. Already-consumed slots ({@code slot_no < from})
     * stay BOOKED. Must not {@code fire()}.
     */
    public ReleaseResult releaseUnconsumed(long orderId, int fromSlotNo) {
        return inStoreTx(() -> doReleaseUnconsumed(orderId, fromSlotNo));
    }

    /**
     * Unpaid add-on hold timeout. Must not {@code fire()}.
     */
    public ReleaseResult releaseAddOnHold(long addHoldId) {
        return inStoreTx(() -> doReleaseAddOnHold(addHoldId));
    }

    /** Caller already opened store work + TX. Used by fire() after CAS. */
    public ReleaseResult releaseLockInOpenTx(long holdId) {
        return doReleaseLock(holdId);
    }

    public ConfirmPaidResult confirmPaidSlotsInOpenTx(long orderId) {
        return doConfirmPaidSlots(orderId);
    }

    public ReleaseResult releaseUnconsumedInOpenTx(long orderId, int fromSlotNo) {
        return doReleaseUnconsumed(orderId, fromSlotNo);
    }

    public ReleaseResult releaseAddOnHoldInOpenTx(long addHoldId) {
        return doReleaseAddOnHold(addHoldId);
    }

    /** Dual-table expired LOCKED holds (scan caller fires or force-frees). */
    public List<Long> findExpiredLockedHoldIds() {
        return List.copyOf(store.findExpiredLockedHoldIds(clock.now(), SCAN_BATCH));
    }

    public SlotOccupyStore.BookingOrderRef findOrderByHoldId(long holdId) {
        return store.findOrderByHoldId(holdId);
    }

    public SlotOccupyStore.BookingOrderRef findOrderByAddOnHoldId(long holdId) {
        return store.findOrderByAddOnHoldId(holdId);
    }

    public SlotOccupyStore.BookingOrderRef findOrderById(long orderId) {
        return store.findOrderById(orderId);
    }

    public List<SlotOccupyStore.BookingOrderRef> listOrdersByCustomer(long customerId) {
        return store.listOrdersByCustomer(customerId);
    }

    public void noteStalePaidLocked() {
        incStalePaid();
    }

    /**
     * Dual-table expired LOCKED scan without {@code fire()} (Law A).
     * Orphan → forceFree; PENDING_PAY / CLOSED leftover → ReleaseLock;
     * add-on hold → ReleaseAddOnHold; paid → skip. Production scan uses
     * {@code SlotScanJob} + fire.
     */
    public SlotScanResult scanExpiredLocks() {
        List<Long> holds = store.findExpiredLockedHoldIds(clock.now(), SCAN_BATCH);
        int orphans = 0;
        int pending = 0;
        int stale = 0;
        int addon = 0;
        for (long holdId : holds) {
            BookingOrderRef byHold = store.findOrderByHoldId(holdId);
            BookingOrderRef byAddon = store.findOrderByAddOnHoldId(holdId);
            if (byHold == null && byAddon == null) {
                forceFreeByHold(holdId);
                orphans++;
            } else if (byAddon != null && byAddon.addOnHoldId() != null && byAddon.addOnHoldId() == holdId) {
                releaseAddOnHold(holdId);
                addon++;
            } else if (byHold != null && mayReleaseUnpaidLocked(byHold.status())) {
                ReleaseResult r = releaseLock(holdId);
                if (r.skipped()) {
                    stale++;
                    incStalePaid();
                } else {
                    pending++;
                }
            } else {
                stale++;
                incStalePaid();
            }
        }
        return new SlotScanResult(List.copyOf(holds), orphans, pending, stale, addon);
    }

    /**
     * ReleaseLock / forceFreeByHold / leave / pay: same store+date key as lockNew.
     */
    public void onRelease(long storeId, LocalDate date) {
        invalidateAvailability(storeId, date);
    }

    public void invalidateAvailability(long storeId, LocalDate date) {
        evictAvail(storeId, date);
    }

    private LockNewResult doLockNew(LockNewCommand cmd) {
        store.beginWork();
        try {
            IdempotencyBegin idem = beginIdempotent(SCOPE_BOOKING, cmd.requestId());
            if (idem.replay != null) {
                store.commitWork();
                return idem.replay;
            }

            ProjectRef project = store.loadProject(cmd.projectId());
            if (project == null) {
                throw new ApiException(ErrorCodes.NOT_FOUND, "项目不存在");
            }
            TherapistRef therapist = store.loadTherapist(cmd.therapistId());
            if (therapist == null) {
                throw new ApiException(ErrorCodes.NOT_FOUND, "技师不存在");
            }

            OccupySpec spec = OccupySpec.of(project.durationMinutes(), project.bufferMinutes());
            List<Integer> slotNos = spec.slotNos(cmd.startSlotNo());
            LocalDateTime now = clock.now();
            LocalDateTime expireAt = now.plusMinutes(LOCK_MINUTES);
            long payableFen = Pricing.priceFen(
                    store.loadSlotPriceOverride(cmd.therapistId(), cmd.date(), cmd.startSlotNo()),
                    store.loadStoreProjectPrice(cmd.storeId(), cmd.projectId()),
                    project.priceFen());

            long orderId = ids.getAsLong();
            long holdId = ids.getAsLong();

            List<SlotRow> trows = store.lockFreeTherapistSlots(cmd.therapistId(), cmd.date(), slotNos);
            if (trows.size() != spec.slotCount()
                    || store.occupancyExists(ResourceType.THERAPIST, cmd.therapistId(), cmd.date(), slotNos)) {
                throw new ApiException(ErrorCodes.SLOT_UNAVAILABLE, "技师时段不可用");
            }
            int tlocked = store.casLockTherapistSlots(
                    cmd.therapistId(), cmd.date(), slotNos, orderId, holdId, expireAt, now);
            if (tlocked != spec.slotCount()) {
                throw new ApiException(ErrorCodes.SLOT_UNAVAILABLE, "技师时段不可用");
            }
            try {
                insertOccupancy(ResourceType.THERAPIST, cmd.therapistId(), cmd.date(), slotNos, orderId, holdId, now);
            } catch (DuplicateOccupancyException ex) {
                throw new ApiException(ErrorCodes.SLOT_UNAVAILABLE, "技师时段不可用");
            }

            BedRef chosen = pickFreeBed(cmd.storeId(), cmd.date(), slotNos, spec, orderId, holdId, expireAt, now);
            if (chosen == null) {
                throw new ApiException(ErrorCodes.NO_FREE_BED, "无空闲床位");
            }

            String orderNo = orderNo(cmd.date(), orderId);
            store.insertOrder(new BookingOrderInsert(
                    orderId, orderNo, cmd.requestId(), holdId,
                    cmd.customerId(), cmd.storeId(), cmd.therapistId(), therapist.homeStoreId(),
                    chosen.id(), chosen.roomId(), ORDER_PENDING_PAY, cmd.source(),
                    cmd.date(), cmd.startSlotNo(), spec.endSlotNo(cmd.startSlotNo()), spec.bufferSlots(),
                    payableFen, payableFen, expireAt, now));
            store.insertOrderItem(new OrderItemInsert(
                    ids.getAsLong(), orderId, ITEM_PROJECT, project.id(), project.name(),
                    project.durationMinutes(), project.bufferMinutes(), 1,
                    payableFen, payableFen,
                    cmd.startSlotNo(), spec.endSlotNo(cmd.startSlotNo()), now));
            store.insertDelayedJob(new DelayedJobInsert(
                    ids.getAsLong(), JOB_RELEASE_LOCK, "hold:" + holdId,
                    "{\"orderId\":" + orderId + ",\"holdId\":" + holdId + "}",
                    expireAt, "PENDING", now));

            LockNewResult result = new LockNewResult(
                    orderId, orderNo, holdId, chosen.id(), chosen.roomId(),
                    ORDER_PENDING_PAY, expireAt.atZone(AppClock.SHANGHAI).toOffsetDateTime().toString(),
                    payableFen, cmd.startSlotNo(), spec.endSlotNo(cmd.startSlotNo()),
                    spec.bufferSlots(), false);
            finishIdempotent(SCOPE_BOOKING, cmd.requestId(), idem.version, result);
            store.commitWork();
            return result;
        } catch (RuntimeException ex) {
            store.rollbackWork();
            throw ex;
        }
    }

    private BedRef pickFreeBed(
            long storeId,
            LocalDate date,
            List<Integer> slotNos,
            OccupySpec spec,
            long orderId,
            long holdId,
            LocalDateTime expireAt,
            LocalDateTime now
    ) {
        for (BedRef bed : store.listBeds(storeId)) {
            List<SlotRow> brows = store.lockFreeBedSlots(bed.id(), date, slotNos);
            if (brows.size() != spec.slotCount()) {
                continue;
            }
            if (store.occupancyExists(ResourceType.BED, bed.id(), date, slotNos)) {
                continue;
            }
            int locked = store.casLockBedSlots(
                    bed.id(), date, slotNos, orderId, holdId, expireAt, now);
            if (locked != spec.slotCount()) {
                continue;
            }
            try {
                insertOccupancy(ResourceType.BED, bed.id(), date, slotNos, orderId, holdId, now);
            } catch (DuplicateOccupancyException ex) {
                store.revertBedHold(bed.id(), holdId, now);
                continue;
            }
            return bed;
        }
        return null;
    }

    private void insertOccupancy(
            String resourceType,
            long resourceId,
            LocalDate date,
            List<Integer> slotNos,
            long orderId,
            long holdId,
            LocalDateTime now
    ) {
        for (int slotNo : slotNos) {
            store.insertOccupancy(new OccupancyInsert(
                    ids.getAsLong(), resourceType, resourceId, date, slotNo, orderId, holdId, now));
        }
    }

    IdempotencyBegin beginIdempotent(String scope, String requestId) {
        LocalDateTime now = clock.now();
        boolean inserted = store.insertIdempotency(new IdemInsert(
                ids.getAsLong(), scope, requestId, "PROCESSING", 0, instanceId,
                now, now, now.plusSeconds(IDEMPOTENT_TAKEOVER_SECONDS)));
        if (inserted) {
            return IdempotencyBegin.proceed(0);
        }
        IdemRow rec = store.lockIdempotency(scope, requestId);
        if (rec == null) {
            throw new ApiException(ErrorCodes.LOCK_CONFLICT, "锁冲突，请重试");
        }
        if ("DONE".equals(rec.status())) {
            return IdempotencyBegin.replay(parseReplay(rec.responseBody()));
        }
        if ("PROCESSING".equals(rec.status()) && rec.expireAt() != null && rec.expireAt().isAfter(now)) {
            throw new ApiException(ErrorCodes.LOCK_CONFLICT, "锁冲突，请重试");
        }
        int n = store.takeoverIdempotency(
                scope, requestId, rec.version(), now.plusSeconds(IDEMPOTENT_TAKEOVER_SECONDS), now, instanceId);
        if (n == 0) {
            throw new ApiException(ErrorCodes.LOCK_CONFLICT, "锁冲突，请重试");
        }
        BookingOrderRef existing = store.findOrderByRequestId(requestId);
        if (existing != null) {
            LockNewResult body = toResult(existing);
            finishIdempotent(scope, requestId, rec.version() + 1, body);
            return IdempotencyBegin.replay(body);
        }
        return IdempotencyBegin.proceed(rec.version() + 1);
    }

    void finishIdempotent(String scope, String requestId, int version, LockNewResult body) {
        store.finishIdempotent(scope, requestId, version, toJson(body), clock.now());
    }

    private ReleaseResult doReleaseLock(long holdId) {
        BookingOrderRef order = store.lockOrderByHoldId(holdId);
        if (order == null) {
            ReleaseResult orphan = doForceFreeByHold(holdId);
            String outcome = ReleaseResult.IDEMPOTENT.equals(orphan.outcome())
                    ? ReleaseResult.IDEMPOTENT
                    : ReleaseResult.ORPHAN_FREED;
            return new ReleaseResult(
                    holdId, outcome,
                    orphan.occupancyDeleted(), orphan.therapistFreed(), orphan.bedFreed());
        }
        if (!mayReleaseUnpaidLocked(order.status())) {
            return new ReleaseResult(holdId, ReleaseResult.SKIPPED_NOT_PENDING, 0, 0, 0);
        }
        return freeLockedHold(holdId, ReleaseResult.FREED);
    }

    /**
     * fire() writes CLOSED first then calls ReleaseLock. PENDING_PAY is the
     * pre-fire / scan-direct path. BOOKED/IN_SERVICE keep occupancy.
     */
    static boolean mayReleaseUnpaidLocked(String status) {
        return ORDER_PENDING_PAY.equals(status) || ORDER_CLOSED.equals(status);
    }

    private ReleaseResult doForceFreeByHold(long holdId) {
        store.lockOrderByHoldId(holdId);
        return freeLockedHold(holdId, ReleaseResult.ORPHAN_FREED);
    }

    private void incStalePaid() {
        if (stalePaid != null) {
            stalePaid.increment();
        }
    }

    private ReleaseResult freeLockedHold(long holdId, String freedOutcome) {
        SlotHoldMeta meta = store.findHoldSlotMeta(holdId);
        int occ = store.deleteOccupancyForLockedHold(holdId);
        LocalDateTime now = clock.now();
        int therapist = store.freeLockedTherapistSlots(holdId, now);
        int bed = store.freeLockedBedSlots(holdId, now);
        evictMeta(meta);
        String outcome = (occ == 0 && therapist == 0 && bed == 0)
                ? ReleaseResult.IDEMPOTENT
                : freedOutcome;
        return new ReleaseResult(holdId, outcome, occ, therapist, bed);
    }

    private ReleaseResult doReleaseUnconsumed(long orderId, int fromSlotNo) {
        BookingOrderRef order = store.lockOrderById(orderId);
        if (order == null) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "订单不存在");
        }
        int occ = store.deleteOccupancyFromSlot(orderId, fromSlotNo);
        LocalDateTime now = clock.now();
        int therapist = store.freeOrderTherapistSlotsFrom(orderId, fromSlotNo, now);
        int bed = store.freeOrderBedSlotsFrom(orderId, fromSlotNo, now);
        evictAvail(order.storeId(), order.serviceDate());
        String outcome = (occ == 0 && therapist == 0 && bed == 0)
                ? ReleaseResult.IDEMPOTENT
                : ReleaseResult.FREED;
        return new ReleaseResult(order.holdId(), outcome, occ, therapist, bed);
    }

    private ReleaseResult doReleaseAddOnHold(long addHoldId) {
        BookingOrderRef order = store.findOrderByAddOnHoldId(addHoldId);
        if (order == null || order.addOnHoldId() == null) {
            return new ReleaseResult(addHoldId, ReleaseResult.IDEMPOTENT, 0, 0, 0);
        }
        store.lockOrderById(order.id());
        int oldEnd = order.endSlotNo();
        int bufferFrom = oldEnd - order.bufferSlots();
        LocalDateTime now = clock.now();
        int occ = store.deleteOccupancyForHoldFromSlot(addHoldId, oldEnd);
        int therapist = store.freeHoldTherapistSlotsFrom(addHoldId, oldEnd, now);
        int bed = store.freeHoldBedSlotsFrom(addHoldId, oldEnd, now);
        store.restoreBufferSlots(order.id(), bufferFrom, oldEnd, order.holdId(), now);
        store.reassignOccupancyHold(order.id(), bufferFrom, oldEnd, order.holdId());
        store.clearAddOnHold(order.id(), now);
        store.deleteUnpaidAddOnItems(order.id(), bufferFrom);
        evictAvail(order.storeId(), order.serviceDate());
        String outcome = (occ == 0 && therapist == 0 && bed == 0)
                ? ReleaseResult.IDEMPOTENT
                : ReleaseResult.FREED;
        return new ReleaseResult(addHoldId, outcome, occ, therapist, bed);
    }

    private ConfirmPaidResult doConfirmPaidSlots(long orderId) {
        BookingOrderRef order = store.lockOrderById(orderId);
        if (order == null) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "订单不存在");
        }
        int serviceEnd = order.startSlotNo()
                + (order.endSlotNo() - order.startSlotNo() - order.bufferSlots());
        LocalDateTime now = clock.now();
        int therapist = store.confirmPaidTherapistSlots(order.id(), order.holdId(), serviceEnd, now);
        int bed = store.confirmPaidBedSlots(order.id(), order.holdId(), serviceEnd, now);
        int jobs = store.markReleaseLockJobDone(order.holdId(), now);
        evictAvail(order.storeId(), order.serviceDate());
        return new ConfirmPaidResult(order.id(), order.holdId(), therapist, bed, jobs);
    }

    private <T> T inStoreTx(Supplier<T> work) {
        return retryDeadlock(() -> inTx(() -> {
            store.beginWork();
            try {
                T result = work.get();
                store.commitWork();
                return result;
            } catch (RuntimeException ex) {
                store.rollbackWork();
                throw ex;
            }
        }));
    }

    private void evictMeta(SlotHoldMeta meta) {
        if (meta != null) {
            evictAvail(meta.storeId(), meta.slotDate());
        }
    }

    private <T> T inTx(Supplier<T> work) {
        if (tx == null) {
            return work.get();
        }
        return tx.execute(status -> work.get());
    }

    private <T> T retryDeadlock(Supplier<T> work) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= DEADLOCK_RETRIES; attempt++) {
            try {
                return work.get();
            } catch (RuntimeException ex) {
                if (!isDeadlock(ex) || attempt == DEADLOCK_RETRIES) {
                    if (isDeadlock(ex)) {
                        throw new ApiException(ErrorCodes.LOCK_CONFLICT, "锁冲突，请重试");
                    }
                    throw ex;
                }
                last = ex;
            }
        }
        throw last != null ? last : new ApiException(ErrorCodes.LOCK_CONFLICT, "锁冲突，请重试");
    }

    static boolean isDeadlock(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof DeadlockLoserDataAccessException) {
                return true;
            }
            if (t instanceof SQLException sql && sql.getErrorCode() == 1213) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null && msg.toLowerCase().contains("deadlock")) {
                return true;
            }
            if (t instanceof DataAccessException && msg != null && msg.contains("1213")) {
                return true;
            }
        }
        return false;
    }

    private void evictAvail(long storeId, LocalDate date) {
        if (availCache != null) {
            availCache.invalidate(storeId, date);
            return;
        }
        if (redis == null) {
            return;
        }
        try {
            ScanOptions opts = ScanOptions.scanOptions()
                    .match(AvailabilityCache.redisPattern(storeId, date))
                    .count(64)
                    .build();
            Set<String> keys = new HashSet<>();
            try (Cursor<String> cursor = redis.scan(opts)) {
                while (cursor.hasNext()) {
                    keys.add(cursor.next());
                }
            }
            if (!keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (RuntimeException ignored) {
            // Availability cache is best-effort (PR3d).
        }
    }

    static String orderNo(LocalDate date, long orderId) {
        return "JS" + date.format(ORDER_DAY) + String.format("%08d", Math.floorMod(orderId, 100_000_000L));
    }

    private static LockNewResult toResult(BookingOrderRef order) {
        return new LockNewResult(
                order.id(), order.orderNo(), order.holdId(), order.bedId(), order.roomId(),
                order.status(),
                order.lockExpireAt() == null
                        ? null
                        : order.lockExpireAt().atZone(AppClock.SHANGHAI).toOffsetDateTime().toString(),
                order.payableFen(), order.startSlotNo(), order.endSlotNo(), order.bufferSlots(), true);
    }

    static String toJson(LockNewResult result) {
        try {
            return JSON.writeValueAsString(result.asReplay() == result
                    ? result
                    : new LockNewResult(
                    result.orderId(), result.orderNo(), result.holdId(), result.bedId(), result.roomId(),
                    result.status(), result.lockExpireAt(), result.payableFen(),
                    result.startSlotNo(), result.endSlotNo(), result.bufferSlots(), false));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static LockNewResult parseReplay(String json) {
        if (json == null || json.isBlank()) {
            throw new ApiException(ErrorCodes.LOCK_CONFLICT, "锁冲突，请重试");
        }
        try {
            return JSON.readValue(json, LockNewResult.class).asReplay();
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCodes.INTERNAL, "幂等回放失败");
        }
    }

    record IdempotencyBegin(int version, LockNewResult replay) {
        static IdempotencyBegin proceed(int version) {
            return new IdempotencyBegin(version, null);
        }

        static IdempotencyBegin replay(LockNewResult body) {
            return new IdempotencyBegin(0, body.asReplay());
        }
    }

    public ExtendOwnResult extendOwn(long orderId, long projectId, int slotCount, boolean cash) {
        return extendOwn(orderId, projectId, slotCount, cash, null);
    }

    public ExtendOwnResult extendOwn(
            long orderId, long projectId, int slotCount, boolean cash, String requestId) {
        if (slotCount < 1) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "durationMinutes 必须是 15 的倍数且 ≥15");
        }
        BookingOrderRef peek = store.findOrderById(orderId);
        if (peek == null) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "订单不存在");
        }
        String token = dayLock.tryAcquire(peek.therapistId(), peek.serviceDate());
        if (token == null) {
            throw new ApiException(ErrorCodes.LOCK_CONFLICT, "锁冲突，请重试");
        }
        try {
            return retryDeadlock(() -> inTx(() -> {
                store.beginWork();
                try {
                    ExtendOwnResult result = doExtendOwn(orderId, projectId, slotCount, cash, requestId);
                    store.commitWork();
                    return result;
                } catch (RuntimeException ex) {
                    store.rollbackWork();
                    throw ex;
                }
            }));
        } finally {
            dayLock.release(peek.therapistId(), peek.serviceDate(), token);
            invalidateAvailability(peek.storeId(), peek.serviceDate());
        }
    }

    public ConfirmPaidResult confirmPaidAddOn(long orderId) {
        return inStoreTx(() -> doConfirmPaidAddOn(orderId));
    }

    public ConfirmPaidResult confirmPaidAddOnInOpenTx(long orderId) {
        return doConfirmPaidAddOn(orderId);
    }

    private ExtendOwnResult doExtendOwn(
            long orderId, long projectId, int slotCount, boolean cash, String requestId) {
        AddonIdem begin = beginAddonIdempotent(requestId);
        if (begin.replay != null) {
            return begin.replay;
        }
        BookingOrderRef order = store.lockOrderById(orderId);
        if (order == null) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "订单不存在");
        }
        if (!ORDER_IN_SERVICE.equals(order.status())) {
            throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "非法状态转移");
        }
        if (order.addOnHoldId() != null) {
            throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "已有未支付加钟");
        }
        ProjectRef project = store.loadProject(projectId);
        if (project == null) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "项目不存在");
        }
        int B = order.bufferSlots();
        int oldEnd = order.endSlotNo();
        int newEnd = oldEnd + slotCount;
        List<Integer> oldBuffer = range(oldEnd - B, oldEnd);
        List<Integer> newFree = range(oldEnd, newEnd);
        LocalDateTime now = clock.now();
        LocalDateTime expireAt = cash ? null : now.plusMinutes(LOCK_MINUTES);
        long addHold = ids.getAsLong();
        long amountFen = addOnPrice(project, slotCount);

        occupyOwnBuffer(order, oldBuffer, addHold, cash, newEnd, expireAt, now);
        occupyNewTail(order, newFree, slotCount, addHold, cash, newEnd, expireAt, now);

        store.insertOrderItem(new OrderItemInsert(
                ids.getAsLong(), order.id(), ITEM_ADD_ON, project.id(), project.name(),
                slotCount * 15, 0, slotCount,
                slotCount == 0 ? 0 : amountFen / slotCount, amountFen,
                oldEnd - B, newEnd, now));

        String paymentNo = null;
        if (cash) {
            long payId = ids.getAsLong();
            paymentNo = "P" + payId;
            store.insertCashPayment(payId, paymentNo, order.id(), amountFen, now);
            store.applyCashAddOn(order.id(), newEnd, amountFen, now);
        } else {
            store.setAddOnHold(order.id(), addHold, now);
            store.insertDelayedJob(new DelayedJobInsert(
                    ids.getAsLong(), JOB_RELEASE_ADDON, "hold:" + addHold,
                    "{\"orderId\":" + order.id() + ",\"holdId\":" + addHold + "}",
                    now.plusMinutes(LOCK_MINUTES), "PENDING", now));
        }
        ExtendOwnResult result = new ExtendOwnResult(
                order.id(), addHold, oldEnd, cash ? newEnd : oldEnd,
                slotCount * 15, amountFen, cash, paymentNo, false);
        finishAddonIdempotent(requestId, begin.version, result);
        return result;
    }

    private void occupyOwnBuffer(
            BookingOrderRef order,
            List<Integer> oldBuffer,
            long addHold,
            boolean cash,
            int newEnd,
            LocalDateTime expireAt,
            LocalDateTime now
    ) {
        if (oldBuffer.isEmpty()) {
            return;
        }
        List<OwnedSlotRow> trows = store.lockTherapistSlots(
                order.therapistId(), order.serviceDate(), oldBuffer);
        List<OwnedSlotRow> brows = store.lockBedSlots(
                order.bedId(), order.serviceDate(), oldBuffer);
        if (!ownBuffer(trows, order.id(), oldBuffer.size())
                || !ownBuffer(brows, order.id(), oldBuffer.size())) {
            throw new ApiException(ErrorCodes.ADD_ON_CONFLICT, "后续格冲突");
        }
        for (int slotNo : oldBuffer) {
            String dest = destStatus(cash, slotNo, newEnd, order.bufferSlots());
            if (store.updateTherapistSlotDest(
                    order.therapistId(), order.serviceDate(), slotNo,
                    SlotStatus.BUFFER, order.id(), dest, order.id(), addHold, expireAt, now) != 1
                    || store.updateBedSlotDest(
                    order.bedId(), order.serviceDate(), slotNo,
                    SlotStatus.BUFFER, order.id(), dest, order.id(), addHold, expireAt, now) != 1) {
                throw new ApiException(ErrorCodes.ADD_ON_CONFLICT, "后续格冲突");
            }
        }
        store.reassignOccupancyHold(order.id(), oldBuffer.getFirst(), oldBuffer.getLast() + 1, addHold);
    }

    private void occupyNewTail(
            BookingOrderRef order,
            List<Integer> newFree,
            int slotCount,
            long addHold,
            boolean cash,
            int newEnd,
            LocalDateTime expireAt,
            LocalDateTime now
    ) {
        List<SlotRow> trows = store.lockFreeTherapistSlots(
                order.therapistId(), order.serviceDate(), newFree);
        if (trows.size() != slotCount
                || store.occupancyExists(
                ResourceType.THERAPIST, order.therapistId(), order.serviceDate(), newFree)) {
            throw new ApiException(ErrorCodes.ADD_ON_CONFLICT, "后续格冲突");
        }
        List<SlotRow> brows = store.lockFreeBedSlots(order.bedId(), order.serviceDate(), newFree);
        if (brows.size() != slotCount
                || store.occupancyExists(ResourceType.BED, order.bedId(), order.serviceDate(), newFree)) {
            throw new ApiException(ErrorCodes.ADD_ON_CONFLICT, "后续格冲突");
        }
        for (int slotNo : newFree) {
            String dest = destStatus(cash, slotNo, newEnd, order.bufferSlots());
            if (store.updateTherapistSlotDest(
                    order.therapistId(), order.serviceDate(), slotNo,
                    SlotStatus.FREE, null, dest, order.id(), addHold, expireAt, now) != 1
                    || store.updateBedSlotDest(
                    order.bedId(), order.serviceDate(), slotNo,
                    SlotStatus.FREE, null, dest, order.id(), addHold, expireAt, now) != 1) {
                throw new ApiException(ErrorCodes.ADD_ON_CONFLICT, "后续格冲突");
            }
        }
        try {
            insertOccupancy(ResourceType.THERAPIST, order.therapistId(), order.serviceDate(),
                    newFree, order.id(), addHold, now);
            insertOccupancy(ResourceType.BED, order.bedId(), order.serviceDate(),
                    newFree, order.id(), addHold, now);
        } catch (DuplicateOccupancyException ex) {
            throw new ApiException(ErrorCodes.ADD_ON_CONFLICT, "后续格冲突");
        }
    }

    private ConfirmPaidResult doConfirmPaidAddOn(long orderId) {
        BookingOrderRef order = store.lockOrderById(orderId);
        if (order == null) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "订单不存在");
        }
        if (order.addOnHoldId() == null) {
            return new ConfirmPaidResult(order.id(), 0L, 0, 0, 0);
        }
        OrderItemInsert item = store.findLatestAddOnItem(order.id());
        if (item == null) {
            throw new ApiException(ErrorCodes.ADD_ON_CONFLICT, "后续格冲突");
        }
        int newEnd = item.endSlotNo();
        int serviceEnd = newEnd - order.bufferSlots();
        LocalDateTime now = clock.now();
        int therapist = store.confirmPaidTherapistSlots(order.id(), order.addOnHoldId(), serviceEnd, now);
        int bed = store.confirmPaidBedSlots(order.id(), order.addOnHoldId(), serviceEnd, now);
        store.applyPaidAddOn(order.id(), newEnd, item.amountFen(), now);
        int jobs = store.markReleaseAddonJobDone(order.addOnHoldId(), now);
        evictAvail(order.storeId(), order.serviceDate());
        return new ConfirmPaidResult(order.id(), order.addOnHoldId(), therapist, bed, jobs);
    }

    static long addOnPrice(ProjectRef project, int slotCount) {
        if (project.addOnPriceFen() != null) {
            return project.addOnPriceFen() * (long) slotCount;
        }
        int units = Math.max(1, project.durationMinutes() / 15);
        return project.priceFen() * (long) slotCount / units;
    }

    static String destStatus(boolean cash, int slotNo, int newEnd, int bufferSlots) {
        if (!cash) {
            return SlotStatus.LOCKED;
        }
        return slotNo >= newEnd - bufferSlots ? SlotStatus.BUFFER : SlotStatus.BOOKED;
    }

    private static boolean ownBuffer(List<OwnedSlotRow> rows, long orderId, int expected) {
        if (rows.size() != expected) {
            return false;
        }
        for (OwnedSlotRow row : rows) {
            if (!SlotStatus.BUFFER.equals(row.status())
                    || row.orderId() == null
                    || row.orderId() != orderId) {
                return false;
            }
        }
        return true;
    }

    private static List<Integer> range(int fromInclusive, int toExclusive) {
        if (toExclusive <= fromInclusive) {
            return List.of();
        }
        java.util.ArrayList<Integer> nos = new java.util.ArrayList<>(toExclusive - fromInclusive);
        for (int s = fromInclusive; s < toExclusive; s++) {
            nos.add(s);
        }
        return List.copyOf(nos);
    }

    private AddonIdem beginAddonIdempotent(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return AddonIdem.proceed(0);
        }
        LocalDateTime now = clock.now();
        boolean inserted = store.insertIdempotency(new IdemInsert(
                ids.getAsLong(), SCOPE_ADDON, requestId, "PROCESSING", 0, instanceId,
                now, now, now.plusSeconds(IDEMPOTENT_TAKEOVER_SECONDS)));
        if (inserted) {
            return AddonIdem.proceed(0);
        }
        IdemRow rec = store.lockIdempotency(SCOPE_ADDON, requestId);
        if (rec == null) {
            throw new ApiException(ErrorCodes.LOCK_CONFLICT, "锁冲突，请重试");
        }
        if ("DONE".equals(rec.status())) {
            return AddonIdem.replay(parseAddonReplay(rec.responseBody()));
        }
        if ("PROCESSING".equals(rec.status()) && rec.expireAt() != null && rec.expireAt().isAfter(now)) {
            throw new ApiException(ErrorCodes.LOCK_CONFLICT, "锁冲突，请重试");
        }
        int n = store.takeoverIdempotency(
                SCOPE_ADDON, requestId, rec.version(), now.plusSeconds(IDEMPOTENT_TAKEOVER_SECONDS),
                now, instanceId);
        if (n == 0) {
            throw new ApiException(ErrorCodes.LOCK_CONFLICT, "锁冲突，请重试");
        }
        return AddonIdem.proceed(rec.version() + 1);
    }

    private void finishAddonIdempotent(String requestId, int version, ExtendOwnResult body) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        store.finishIdempotent(SCOPE_ADDON, requestId, version, toAddonJson(body), clock.now());
    }

    static String toAddonJson(ExtendOwnResult result) {
        try {
            ExtendOwnResult stored = result.replay()
                    ? new ExtendOwnResult(
                    result.orderId(), result.addHoldId(), result.oldEndSlotNo(), result.newEndSlotNo(),
                    result.durationMinutes(), result.amountFen(), result.cash(), result.paymentNo(), false)
                    : result;
            return JSON.writeValueAsString(stored);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static ExtendOwnResult parseAddonReplay(String json) {
        if (json == null || json.isBlank()) {
            throw new ApiException(ErrorCodes.LOCK_CONFLICT, "锁冲突，请重试");
        }
        try {
            return JSON.readValue(json, ExtendOwnResult.class).asReplay();
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCodes.INTERNAL, "幂等回放失败");
        }
    }

    record AddonIdem(int version, ExtendOwnResult replay) {
        static AddonIdem proceed(int version) {
            return new AddonIdem(version, null);
        }

        static AddonIdem replay(ExtendOwnResult body) {
            return new AddonIdem(0, body.asReplay());
        }
    }

    public static final String SCOPE_SWAP = "swap-therapist";
    public static final String ORDER_CHECKED_IN = "CHECKED_IN";

    private TreatmentNoteRepository notes;

    @Autowired(required = false)
    public void setTreatmentNotes(TreatmentNoteRepository notes) {
        this.notes = notes;
    }

    /** Move remain therapist slots; do not touch the bed. */
    public SwapTherapistResult swapTherapist(String requestId, long orderId, long newTherapistId, String reason) {
        if (requestId == null || requestId.isBlank()) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "requestId 不能为空");
        }
        BookingOrderRef peek = store.findOrderById(orderId);
        if (peek == null) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "订单不存在");
        }
        String token = dayLock.tryAcquire(newTherapistId, peek.serviceDate());
        if (token == null) {
            throw new ApiException(ErrorCodes.LOCK_CONFLICT, "锁冲突，请重试");
        }
        try {
            return retryDeadlock(() -> inTx(() -> doSwapTherapist(requestId, orderId, newTherapistId, reason)));
        } finally {
            dayLock.release(newTherapistId, peek.serviceDate(), token);
            invalidateAvailability(peek.storeId(), peek.serviceDate());
        }
    }

    private SwapTherapistResult doSwapTherapist(
            String requestId, long orderId, long newTherapistId, String reason) {
        store.beginWork();
        try {
            SwapIdem begin = beginSwapIdempotent(requestId);
            if (begin.replay != null) {
                store.commitWork();
                return begin.replay;
            }

            BookingOrderRef order = store.lockOrderById(orderId);
            if (order == null) {
                throw new ApiException(ErrorCodes.NOT_FOUND, "订单不存在");
            }
            if (!ORDER_CHECKED_IN.equals(order.status()) && !ORDER_IN_SERVICE.equals(order.status())) {
                throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "当前状态不可换技师");
            }
            if (order.therapistId() == newTherapistId) {
                throw new ApiException(ErrorCodes.BAD_REQUEST, "newTherapistId 不能与当前技师相同");
            }
            TherapistRef neu = store.loadTherapist(newTherapistId);
            if (neu == null) {
                throw new ApiException(ErrorCodes.NOT_FOUND, "技师不存在");
            }

            LocalDateTime now = clock.now();
            int fromNo = remainFrom(order, now);
            List<Integer> remain = slotRange(fromNo, order.endSlotNo());
            if (!remain.isEmpty()) {
                moveRemainTherapist(order, newTherapistId, remain, now);
            }
            store.updateTherapist(order.id(), newTherapistId, neu.homeStoreId(), now);
            writeSwapRecords(order, newTherapistId, reason, now);

            SwapTherapistResult result = new SwapTherapistResult(
                    order.id(), order.therapistId(), newTherapistId, fromNo, order.endSlotNo(), false);
            finishSwapIdempotent(requestId, begin.version, result);
            store.commitWork();
            return result;
        } catch (RuntimeException ex) {
            store.rollbackWork();
            throw ex;
        }
    }

    private void moveRemainTherapist(
            BookingOrderRef order, long newTherapistId, List<Integer> remain, LocalDateTime now) {
        List<OwnedSlotRow> trows = store.lockTherapistSlots(newTherapistId, order.serviceDate(), remain);
        if (trows.size() != remain.size()
                || trows.stream().anyMatch(row -> !SlotStatus.FREE.equals(row.status()))) {
            throw new ApiException(ErrorCodes.SLOT_UNAVAILABLE, "技师时段不可用");
        }
        List<SlotRow> oldRows = store.listTherapistSlots(order.therapistId(), order.serviceDate(), remain);
        if (oldRows.size() != remain.size()) {
            throw new ApiException(ErrorCodes.SLOT_UNAVAILABLE, "技师时段不可用");
        }
        for (SlotRow old : oldRows) {
            int n = store.assignTherapistSlot(
                    newTherapistId, order.serviceDate(), old.slotNo(), old.status(),
                    order.id(), order.holdId(), null, now);
            if (n != 1) {
                throw new ApiException(ErrorCodes.SLOT_UNAVAILABLE, "技师时段不可用");
            }
        }
        insertOccupancy(
                ResourceType.THERAPIST, newTherapistId, order.serviceDate(), remain,
                order.id(), order.holdId(), now);
        store.deleteTherapistOccupancy(order.therapistId(), order.serviceDate(), remain);
        store.freeTherapistSlots(order.therapistId(), order.serviceDate(), remain, now);
    }

    private void writeSwapRecords(BookingOrderRef order, long newTherapistId, String reason, LocalDateTime now) {
        if (notes == null) {
            return;
        }
        if (!ORDER_IN_SERVICE.equals(order.status())) {
            return;
        }
        java.time.Instant at = now.atZone(AppClock.SHANGHAI).toInstant();
        notes.markLatestEnded(order.id(), at);
        notes.insertServiceRecord(
                ids.getAsLong(), order.id(), newTherapistId,
                order.customerId(), order.storeId(), at);
        String content = (reason == null || reason.isBlank()) ? "中途换师" : "中途换师：" + reason.trim();
        notes.insertSystemNote(
                ids.getAsLong(), order.id(), order.storeId(), newTherapistId, 0L, content, at);
    }

    public static int remainFrom(BookingOrderRef order, LocalDateTime now) {
        if (!ORDER_IN_SERVICE.equals(order.status())) {
            return order.startSlotNo();
        }
        int current = currentSlotNo(now);
        if (now.toLocalDate().isAfter(order.serviceDate()) || current >= order.endSlotNo()) {
            return order.endSlotNo();
        }
        return Math.max(current, order.startSlotNo());
    }

    private SwapIdem beginSwapIdempotent(String requestId) {
        LocalDateTime now = clock.now();
        boolean inserted = store.insertIdempotency(new IdemInsert(
                ids.getAsLong(), SCOPE_SWAP, requestId, "PROCESSING", 0, instanceId,
                now, now, now.plusSeconds(IDEMPOTENT_TAKEOVER_SECONDS)));
        if (inserted) {
            return SwapIdem.proceed(0);
        }
        IdemRow rec = store.lockIdempotency(SCOPE_SWAP, requestId);
        if (rec == null) {
            throw new ApiException(ErrorCodes.LOCK_CONFLICT, "锁冲突，请重试");
        }
        if ("DONE".equals(rec.status())) {
            return SwapIdem.replay(parseSwapReplay(rec.responseBody()));
        }
        if ("PROCESSING".equals(rec.status()) && rec.expireAt() != null && rec.expireAt().isAfter(now)) {
            throw new ApiException(ErrorCodes.LOCK_CONFLICT, "锁冲突，请重试");
        }
        int n = store.takeoverIdempotency(
                SCOPE_SWAP, requestId, rec.version(), now.plusSeconds(IDEMPOTENT_TAKEOVER_SECONDS), now, instanceId);
        if (n == 0) {
            throw new ApiException(ErrorCodes.LOCK_CONFLICT, "锁冲突，请重试");
        }
        return SwapIdem.proceed(rec.version() + 1);
    }

    private void finishSwapIdempotent(String requestId, int version, SwapTherapistResult body) {
        store.finishIdempotent(SCOPE_SWAP, requestId, version, toSwapJson(body), clock.now());
    }

    static String toSwapJson(SwapTherapistResult result) {
        try {
            SwapTherapistResult stored = result.replay()
                    ? new SwapTherapistResult(
                    result.orderId(), result.oldTherapistId(), result.newTherapistId(),
                    result.fromSlotNo(), result.endSlotNo(), false)
                    : result;
            return JSON.writeValueAsString(stored);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static SwapTherapistResult parseSwapReplay(String json) {
        if (json == null || json.isBlank()) {
            throw new ApiException(ErrorCodes.LOCK_CONFLICT, "锁冲突，请重试");
        }
        try {
            return JSON.readValue(json, SwapTherapistResult.class).asReplay();
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCodes.INTERNAL, "幂等回放失败");
        }
    }

    static int currentSlotNo(LocalDateTime now) {
        return now.getHour() * 4 + now.getMinute() / 15;
    }

    static List<Integer> slotRange(int fromInclusive, int toExclusive) {
        if (toExclusive <= fromInclusive) {
            return List.of();
        }
        List<Integer> slots = new ArrayList<>(toExclusive - fromInclusive);
        for (int i = fromInclusive; i < toExclusive; i++) {
            slots.add(i);
        }
        return slots;
    }

    record SwapIdem(int version, SwapTherapistResult replay) {
        static SwapIdem proceed(int version) {
            return new SwapIdem(version, null);
        }

        static SwapIdem replay(SwapTherapistResult body) {
            return new SwapIdem(0, body.asReplay());
        }
    }
}
