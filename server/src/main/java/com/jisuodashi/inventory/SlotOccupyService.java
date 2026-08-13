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
import com.jisuodashi.inventory.SlotOccupyStore.IdemInsert;
import com.jisuodashi.inventory.SlotOccupyStore.IdemRow;
import com.jisuodashi.inventory.SlotOccupyStore.OccupancyInsert;
import com.jisuodashi.inventory.SlotOccupyStore.OrderItemInsert;
import com.jisuodashi.inventory.SlotOccupyStore.ProjectRef;
import com.jisuodashi.inventory.SlotOccupyStore.SlotRow;
import com.jisuodashi.inventory.SlotOccupyStore.TherapistRef;
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
 */
@Service
public class SlotOccupyService {

    public static final String SCOPE_BOOKING = "booking";
    public static final String JOB_RELEASE_LOCK = "RELEASE_LOCK";
    public static final String ORDER_PENDING_PAY = "PENDING_PAY";
    public static final String ITEM_PROJECT = "PROJECT";
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

    @Autowired
    public SlotOccupyService(
            SlotOccupyStore store,
            TherapistDayLock dayLock,
            SnowflakeIdGenerator ids,
            AppClock clock,
            PlatformTransactionManager txManager,
            AppProperties properties,
            @Autowired(required = false) StringRedisTemplate redis
    ) {
        this(store, dayLock, ids::nextId, clock, new TransactionTemplate(txManager),
                "w" + properties.getSnowflake().getWorkerId(), redis);
    }

    public SlotOccupyService(
            SlotOccupyStore store,
            TherapistDayLock dayLock,
            LongSupplier ids,
            AppClock clock
    ) {
        this(store, dayLock, ids, clock, null, "test", null);
    }

    SlotOccupyService(
            SlotOccupyStore store,
            TherapistDayLock dayLock,
            LongSupplier ids,
            AppClock clock,
            TransactionTemplate tx,
            String instanceId,
            StringRedisTemplate redis
    ) {
        this.store = store;
        this.dayLock = dayLock;
        this.ids = ids;
        this.clock = clock;
        this.tx = tx;
        this.instanceId = instanceId;
        this.redis = redis;
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
            evictAvail(cmd.storeId(), cmd.date());
        }
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

    private LockNewResult inTx(Supplier<LockNewResult> work) {
        if (tx == null) {
            return work.get();
        }
        return tx.execute(status -> work.get());
    }

    private LockNewResult retryDeadlock(Supplier<LockNewResult> work) {
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
        if (redis == null) {
            return;
        }
        try {
            ScanOptions opts = ScanOptions.scanOptions()
                    .match("cache:avail:" + storeId + ":" + date + ":*")
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
}
