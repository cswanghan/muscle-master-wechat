package com.jisuodashi.inventory;

import com.jisuodashi.catalog.DemoCatalogIds;
import com.jisuodashi.inventory.DelayedJobStore.DelayedJobRow;
import com.jisuodashi.inventory.SlotOccupyStore.BedRef;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderInsert;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import com.jisuodashi.inventory.SlotOccupyStore.DelayedJobInsert;
import com.jisuodashi.inventory.SlotOccupyStore.SlotHoldMeta;
import com.jisuodashi.inventory.SlotOccupyStore.IdemInsert;
import com.jisuodashi.inventory.SlotOccupyStore.IdemRow;
import com.jisuodashi.inventory.SlotOccupyStore.OccupancyInsert;
import com.jisuodashi.inventory.SlotOccupyStore.OrderChangeLogInsert;
import com.jisuodashi.inventory.SlotOccupyStore.OrderItemInsert;
import com.jisuodashi.inventory.SlotOccupyStore.RescheduleAcquire;
import com.jisuodashi.inventory.SlotOccupyStore.RescheduleSlotKey;
import com.jisuodashi.inventory.SlotOccupyStore.RescheduleSlotRow;
import com.jisuodashi.inventory.SlotOccupyStore.OwnedSlotRow;
import com.jisuodashi.inventory.SlotOccupyStore.ProjectRef;
import com.jisuodashi.inventory.SlotOccupyStore.SlotRow;
import com.jisuodashi.inventory.SlotOccupyStore.TherapistRef;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory CAS + ordered row locks. H2 cannot run V1 MySQL DDL, so lockNew
 * is tested here. {@code dev} profile exposes the same store so POST /c/bookings
 * can call lockNew without MySQL.
 */
@Repository
@Profile("dev")
public class InMemorySlotOccupyStore implements SlotOccupyStore {

    static final LocalDate DEMO_DATE = LocalDate.of(2026, 8, 14);
    static final int OPEN_SLOT = 40;
    static final int CLOSE_SLOT = 88;
    static final long ROOM = 3_100_000_000_000_000_101L;
    static final long BED1 = 3_100_000_000_000_000_201L;
    static final long BED2 = 3_100_000_000_000_000_202L;

    final Map<Long, ProjectRef> projects = new ConcurrentHashMap<>();
    final Map<Long, TherapistRef> therapists = new ConcurrentHashMap<>();
    final Map<Long, BedRef> beds = new ConcurrentHashMap<>();
    final Map<String, MutableSlot> therapistSlots = new ConcurrentHashMap<>();
    final Map<String, MutableSlot> bedSlots = new ConcurrentHashMap<>();
    public final Map<String, OccupancyInsert> occupancies = new ConcurrentHashMap<>();

    public int occupancyCount() {
        return occupancies.size();
    }
    final Map<String, IdemState> idempotency = new ConcurrentHashMap<>();
    final Map<Long, BookingOrderInsert> orders = new ConcurrentHashMap<>();
    final Map<String, BookingOrderInsert> ordersByRequest = new ConcurrentHashMap<>();
    public final List<OrderItemInsert> orderItems = new ArrayList<>();
    final List<DelayedJobInsert> delayedJobs = new ArrayList<>();
    final Map<Long, MutableJob> jobs = new ConcurrentHashMap<>();
    final Map<Long, String> orderStatuses = new ConcurrentHashMap<>();
    final Map<Long, Long> addOnHolds = new ConcurrentHashMap<>();
    final Map<Long, Integer> endSlotNos = new ConcurrentHashMap<>();
    final Map<Long, Long> payableFens = new ConcurrentHashMap<>();
    final Map<Long, Long> paidFens = new ConcurrentHashMap<>();
    final Map<String, Long> storePrices = new ConcurrentHashMap<>();
    final Map<String, Long> slotOverrides = new ConcurrentHashMap<>();
    /** Keys we actually acquired a row lock on (must never include known-busy slots). */
    final Set<String> slotPinAttempts = ConcurrentHashMap.newKeySet();
    /**
     * Test hook: after this many successful BED occupancy inserts, the next BED insert
     * throws; revertBedHold disables the hook so the next bed can succeed.
     */
    volatile int failBedOccupancyAfter = -1;
    private final AtomicInteger occupancyInserts = new AtomicInteger();
    private final AtomicInteger bedOccupancyInserts = new AtomicInteger();

    private final ConcurrentHashMap<String, ReentrantLock> rowLocks = new ConcurrentHashMap<>();
    private final ThreadLocal<Work> work = new ThreadLocal<>();

    @PostConstruct
    void initDemo() {
        seedDemoCatalog();
        seedDemoCalendar();
    }

    public void resetDemoCalendar() {
        occupancies.clear();
        orders.clear();
        ordersByRequest.clear();
        orderItems.clear();
        delayedJobs.clear();
        jobs.clear();
        orderStatuses.clear();
        addOnHolds.clear();
        endSlotNos.clear();
        payableFens.clear();
        paidFens.clear();
        idempotency.clear();
        slotPinAttempts.clear();
        occupancyInserts.set(0);
        bedOccupancyInserts.set(0);
        therapistSlots.clear();
        bedSlots.clear();
        seedDemoCalendar();
    }

    void seedDemoCatalog() {
        seedProject(new ProjectRef(DemoCatalogIds.PROJECT_P60, "全身推拿放松", 60, 15, 19800, 4900L));
        seedProject(new ProjectRef(DemoCatalogIds.PROJECT_P45, "肩颈专项疏通", 45, 15, 12800, 4200L));
        seedProject(new ProjectRef(DemoCatalogIds.PROJECT_P90, "腰背深层理筋", 90, 15, 26800, 4400L));
        seedTherapist(new TherapistRef(DemoCatalogIds.THERAPIST_LIN, DemoCatalogIds.STORE));
        seedTherapist(new TherapistRef(DemoCatalogIds.THERAPIST_CHEN, DemoCatalogIds.STORE));
        seedTherapist(new TherapistRef(DemoCatalogIds.THERAPIST_ZHOU, DemoCatalogIds.STORE));
        seedBed(new BedRef(BED1, DemoCatalogIds.STORE, ROOM, 1));
        seedBed(new BedRef(BED2, DemoCatalogIds.STORE, ROOM, 2));
    }

    void seedDemoCalendar() {
        for (int day = 0; day < 15; day++) {
            LocalDate date = DEMO_DATE.plusDays(day);
            for (long therapist : new long[] {
                    DemoCatalogIds.THERAPIST_LIN, DemoCatalogIds.THERAPIST_CHEN, DemoCatalogIds.THERAPIST_ZHOU}) {
                seedTherapistSlots(therapist, DemoCatalogIds.STORE, date, OPEN_SLOT, CLOSE_SLOT, SlotStatus.FREE);
            }
            seedBedSlots(BED1, DemoCatalogIds.STORE, date, OPEN_SLOT, CLOSE_SLOT, SlotStatus.FREE);
            seedBedSlots(BED2, DemoCatalogIds.STORE, date, OPEN_SLOT, CLOSE_SLOT, SlotStatus.FREE);
        }
    }

    @Override
    public void beginWork() {
        Work w = work.get();
        if (w != null) {
            w.depth++;
            return;
        }
        work.set(new Work());
    }

    @Override
    public void commitWork() {
        Work w = work.get();
        if (w == null) {
            return;
        }
        if (w.depth > 0) {
            w.depth--;
            return;
        }
        w.unlockAll();
        work.remove();
    }

    @Override
    public void rollbackWork() {
        Work w = work.get();
        if (w == null) {
            return;
        }
        if (w.depth > 0) {
            w.depth--;
            return;
        }
        for (int i = w.undos.size() - 1; i >= 0; i--) {
            w.undos.get(i).run();
        }
        w.unlockAll();
        work.remove();
    }

    @Override
    public ProjectRef loadProject(long projectId) {
        return projects.get(projectId);
    }

    @Override
    public TherapistRef loadTherapist(long therapistId) {
        return therapists.get(therapistId);
    }

    @Override
    public List<BedRef> listBeds(long storeId) {
        return beds.values().stream()
                .filter(b -> b.storeId() == storeId)
                .sorted(Comparator.comparingInt(BedRef::sortNo).thenComparingLong(BedRef::id))
                .toList();
    }

    @Override
    public Long loadStoreProjectPrice(long storeId, long projectId) {
        return storePrices.get(storeId + "|" + projectId);
    }

    @Override
    public Long loadSlotPriceOverride(long therapistId, LocalDate date, int startSlotNo) {
        return slotOverrides.get(tkey(therapistId, date, startSlotNo));
    }

    @Override
    public boolean insertIdempotency(IdemInsert row) {
        Work w = requireWork();
        ReentrantLock lock = lockRow(ikey(row.scope(), row.requestId()), w);
        IdemState created = new IdemState(row);
        IdemState prev = idempotency.putIfAbsent(ikey(row.scope(), row.requestId()), created);
        if (prev != null) {
            // Keep the row lock — equivalent to INSERT wait then SELECT FOR UPDATE.
            return false;
        }
        w.undos.add(() -> idempotency.remove(ikey(row.scope(), row.requestId()), created));
        return true;
    }

    @Override
    public IdemRow lockIdempotency(String scope, String requestId) {
        Work w = requireWork();
        lockRow(ikey(scope, requestId), w);
        IdemState rec = idempotency.get(ikey(scope, requestId));
        return rec == null ? null : rec.toRow();
    }

    @Override
    public int takeoverIdempotency(
            String scope, String requestId, int expectedVersion,
            LocalDateTime expireAt, LocalDateTime now, String lockedBy) {
        IdemState rec = idempotency.get(ikey(scope, requestId));
        if (rec == null || !"PROCESSING".equals(rec.status) || rec.version != expectedVersion) {
            return 0;
        }
        if (rec.expireAt != null && rec.expireAt.isAfter(now)) {
            return 0;
        }
        int prevVersion = rec.version;
        LocalDateTime prevExpire = rec.expireAt;
        String prevBy = rec.lockedBy;
        rec.expireAt = expireAt;
        rec.version = expectedVersion + 1;
        rec.lockedBy = lockedBy;
        rec.updatedAt = now;
        requireWork().undos.add(() -> {
            rec.version = prevVersion;
            rec.expireAt = prevExpire;
            rec.lockedBy = prevBy;
        });
        return 1;
    }

    @Override
    public BookingOrderRef findOrderByRequestId(String requestId) {
        BookingOrderInsert row = ordersByRequest.get(requestId);
        if (row == null) {
            return null;
        }
        return toRef(row);
    }

    @Override
    public int finishIdempotent(String scope, String requestId, int version, String responseBody, LocalDateTime now) {
        IdemState rec = idempotency.get(ikey(scope, requestId));
        if (rec == null || !"PROCESSING".equals(rec.status) || rec.version != version) {
            return 0;
        }
        String prevStatus = rec.status;
        String prevBody = rec.responseBody;
        rec.status = "DONE";
        rec.responseBody = responseBody;
        rec.updatedAt = now;
        requireWork().undos.add(() -> {
            rec.status = prevStatus;
            rec.responseBody = prevBody;
        });
        return 1;
    }

    @Override
    public List<SlotRow> lockFreeTherapistSlots(long therapistId, LocalDate date, List<Integer> slotNos) {
        return lockFree(therapistSlots, therapistId, date, slotNos, true);
    }

    @Override
    public List<SlotRow> lockFreeBedSlots(long bedId, LocalDate date, List<Integer> slotNos) {
        return lockFree(bedSlots, bedId, date, slotNos, false);
    }

    private List<SlotRow> lockFree(
            Map<String, MutableSlot> slots,
            long resourceId,
            LocalDate date,
            List<Integer> slotNos,
            boolean therapist
    ) {
        Work w = requireWork();
        List<Integer> ordered = slotNos.stream().sorted().toList();
        List<SlotRow> locked = new ArrayList<>();
        for (int slotNo : ordered) {
            String key = therapist ? tkey(resourceId, date, slotNo) : bkey(resourceId, date, slotNo);
            MutableSlot peek = slots.get(key);
            if (peek == null || !SlotStatus.FREE.equals(peek.status)) {
                continue;
            }
            lockRow(key, w);
            MutableSlot slot = slots.get(key);
            if (slot != null && SlotStatus.FREE.equals(slot.status)) {
                locked.add(new SlotRow(slotNo, slot.status));
            } else {
                ReentrantLock lock = rowLocks.get(key);
                if (lock != null && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                    w.locks.remove(lock);
                }
            }
        }
        return locked;
    }

    @Override
    public boolean occupancyExists(String resourceType, long resourceId, LocalDate date, List<Integer> slotNos) {
        for (int slotNo : slotNos) {
            if (occupancies.containsKey(okey(resourceType, resourceId, date, slotNo))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int casLockTherapistSlots(
            long therapistId, LocalDate date, List<Integer> slotNos,
            long orderId, long holdId, LocalDateTime expireAt, LocalDateTime now) {
        return casLock(therapistSlots, true, therapistId, date, slotNos, orderId, holdId, expireAt);
    }

    @Override
    public int casLockBedSlots(
            long bedId, LocalDate date, List<Integer> slotNos,
            long orderId, long holdId, LocalDateTime expireAt, LocalDateTime now) {
        return casLock(bedSlots, false, bedId, date, slotNos, orderId, holdId, expireAt);
    }

    private int casLock(
            Map<String, MutableSlot> slots,
            boolean therapist,
            long resourceId,
            LocalDate date,
            List<Integer> slotNos,
            long orderId,
            long holdId,
            LocalDateTime expireAt
    ) {
        Work w = requireWork();
        int n = 0;
        for (int slotNo : slotNos) {
            String key = therapist ? tkey(resourceId, date, slotNo) : bkey(resourceId, date, slotNo);
            MutableSlot slot = slots.get(key);
            if (slot == null || !SlotStatus.FREE.equals(slot.status)) {
                continue;
            }
            Snapshot snap = slot.snapshot();
            slot.status = SlotStatus.LOCKED;
            slot.orderId = orderId;
            slot.holdId = holdId;
            slot.lockExpireAt = expireAt;
            w.undos.add(() -> slot.restore(snap));
            n++;
        }
        return n;
    }

    @Override
    public void insertOccupancy(OccupancyInsert row) {
        if (ResourceType.BED.equals(row.resourceType())
                && failBedOccupancyAfter >= 0
                && bedOccupancyInserts.get() >= failBedOccupancyAfter) {
            throw new DuplicateOccupancyException("injected occupancy fail after " + failBedOccupancyAfter);
        }
        String key = okey(row.resourceType(), row.resourceId(), row.slotDate(), row.slotNo());
        OccupancyInsert prev = occupancies.putIfAbsent(key, row);
        if (prev != null) {
            throw new DuplicateOccupancyException("occupancy exists " + key);
        }
        occupancyInserts.incrementAndGet();
        if (ResourceType.BED.equals(row.resourceType())) {
            bedOccupancyInserts.incrementAndGet();
        }
        requireWork().undos.add(() -> {
            occupancies.remove(key, row);
            occupancyInserts.decrementAndGet();
            if (ResourceType.BED.equals(row.resourceType())) {
                bedOccupancyInserts.decrementAndGet();
            }
        });
    }

    @Override
    public void revertBedHold(long bedId, long holdId, LocalDateTime now) {
        Work w = requireWork();
        occupancies.entrySet().removeIf(e -> {
            OccupancyInsert row = e.getValue();
            if (ResourceType.BED.equals(row.resourceType())
                    && row.resourceId() == bedId
                    && row.holdId() == holdId) {
                occupancyInserts.decrementAndGet();
                bedOccupancyInserts.decrementAndGet();
                return true;
            }
            return false;
        });
        failBedOccupancyAfter = -1;
        for (MutableSlot slot : bedSlots.values()) {
            if (slot.resourceId == bedId && slot.holdId != null && slot.holdId == holdId) {
                Snapshot snap = slot.snapshot();
                slot.status = SlotStatus.FREE;
                slot.orderId = null;
                slot.holdId = null;
                slot.lockExpireAt = null;
                w.undos.add(() -> slot.restore(snap));
            }
        }
    }

    @Override
    public synchronized void insertOrder(BookingOrderInsert row) {
        orders.put(row.id(), row);
        ordersByRequest.put(row.requestId(), row);
        requireWork().undos.add(() -> {
            orders.remove(row.id(), row);
            ordersByRequest.remove(row.requestId(), row);
        });
    }

    @Override
    public synchronized void insertOrderItem(OrderItemInsert row) {
        orderItems.add(row);
        requireWork().undos.add(() -> {
            synchronized (this) {
                orderItems.remove(row);
            }
        });
    }

    @Override
    public synchronized void insertDelayedJob(DelayedJobInsert row) {
        delayedJobs.add(row);
        MutableJob job = MutableJob.from(row);
        jobs.put(row.id(), job);
        requireWork().undos.add(() -> {
            synchronized (this) {
                delayedJobs.remove(row);
                jobs.remove(row.id(), job);
            }
        });
    }

    @Override
    public BookingOrderRef findOrderByHoldId(long holdId) {
        return orders.values().stream()
                .filter(row -> row.holdId() == holdId)
                .findFirst()
                .map(this::toRef)
                .orElse(null);
    }

    @Override
    public BookingOrderRef findOrderByAddOnHoldId(long holdId) {
        return addOnHolds.entrySet().stream()
                .filter(e -> e.getValue() == holdId)
                .map(e -> orders.get(e.getKey()))
                .filter(row -> row != null)
                .findFirst()
                .map(this::toRef)
                .orElse(null);
    }

    @Override
    public BookingOrderRef lockOrderByHoldId(long holdId) {
        Work w = requireWork();
        lockRow("O|hold|" + holdId, w);
        return findOrderByHoldId(holdId);
    }

    @Override
    public BookingOrderRef lockOrderById(long orderId) {
        Work w = requireWork();
        lockRow("O|id|" + orderId, w);
        return findOrderById(orderId);
    }

    @Override
    public List<BookingOrderRef> listOrdersByCustomer(long customerId) {
        return orders.values().stream()
                .filter(row -> row.customerId() == customerId)
                .sorted(Comparator.comparingLong(BookingOrderInsert::id).reversed())
                .map(this::toRef)
                .toList();
    }

    @Override
    public BookingOrderRef findOrderById(long orderId) {
        BookingOrderInsert row = orders.get(orderId);
        return row == null ? null : toRef(row);
    }

    @Override
    public BookingOrderRef findOrderByOrderNo(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return null;
        }
        return orders.values().stream()
                .filter(row -> orderNo.equals(row.orderNo()))
                .findFirst()
                .map(this::toRef)
                .orElse(null);
    }

    @Override
    public List<BookingOrderRef> listOrdersByCustomerId(long customerId) {
        return orders.values().stream()
                .filter(row -> row.customerId() == customerId)
                .sorted(Comparator
                        .comparing(BookingOrderInsert::serviceDate).reversed()
                        .thenComparingInt(BookingOrderInsert::startSlotNo))
                .map(this::toRef)
                .toList();
    }

    @Override
    public int deleteOccupancyForLockedHold(long holdId) {
        Work w = requireWork();
        List<Map.Entry<String, OccupancyInsert>> removed = new ArrayList<>();
        occupancies.entrySet().removeIf(e -> {
            OccupancyInsert row = e.getValue();
            if (row.holdId() != holdId) {
                return false;
            }
            MutableSlot slot = slotFor(row);
            if (slot == null || !SlotStatus.LOCKED.equals(slot.status)) {
                return false;
            }
            removed.add(e);
            return true;
        });
        w.undos.add(() -> {
            for (Map.Entry<String, OccupancyInsert> e : removed) {
                occupancies.putIfAbsent(e.getKey(), e.getValue());
            }
        });
        return removed.size();
    }

    @Override
    public int deleteOccupancyByHold(long holdId) {
        Work w = requireWork();
        List<Map.Entry<String, OccupancyInsert>> removed = new ArrayList<>();
        occupancies.entrySet().removeIf(e -> {
            if (e.getValue().holdId() != holdId) {
                return false;
            }
            removed.add(e);
            return true;
        });
        w.undos.add(() -> {
            for (Map.Entry<String, OccupancyInsert> e : removed) {
                occupancies.putIfAbsent(e.getKey(), e.getValue());
            }
        });
        return removed.size();
    }

    @Override
    public int freeLockedTherapistSlots(long holdId, LocalDateTime now) {
        return freeLocked(therapistSlots, holdId);
    }

    @Override
    public int freeLockedBedSlots(long holdId, LocalDateTime now) {
        return freeLocked(bedSlots, holdId);
    }

    private int freeLocked(Map<String, MutableSlot> slots, long holdId) {
        Work w = requireWork();
        int n = 0;
        for (MutableSlot slot : slots.values()) {
            if (slot.holdId != null && slot.holdId == holdId && SlotStatus.LOCKED.equals(slot.status)) {
                Snapshot snap = slot.snapshot();
                slot.status = SlotStatus.FREE;
                slot.orderId = null;
                slot.holdId = null;
                slot.lockExpireAt = null;
                w.undos.add(() -> slot.restore(snap));
                n++;
            }
        }
        return n;
    }

    @Override
    public List<Long> findExpiredLockedHoldIds(LocalDateTime now, int limit) {
        java.util.TreeSet<Long> ids = new java.util.TreeSet<>();
        collectExpired(therapistSlots, now, ids);
        collectExpired(bedSlots, now, ids);
        return ids.stream().limit(limit).toList();
    }

    @Override
    public int countLockedExpiredBefore(LocalDateTime cutoff) {
        return countExpiredRows(therapistSlots, cutoff) + countExpiredRows(bedSlots, cutoff);
    }

    private static int countExpiredRows(Map<String, MutableSlot> slots, LocalDateTime cutoff) {
        int n = 0;
        for (MutableSlot slot : slots.values()) {
            if (SlotStatus.LOCKED.equals(slot.status)
                    && slot.lockExpireAt != null
                    && slot.lockExpireAt.isBefore(cutoff)) {
                n++;
            }
        }
        return n;
    }

    private static void collectExpired(Map<String, MutableSlot> slots, LocalDateTime now, java.util.Set<Long> ids) {
        for (MutableSlot slot : slots.values()) {
            if (SlotStatus.LOCKED.equals(slot.status)
                    && slot.holdId != null
                    && slot.lockExpireAt != null
                    && slot.lockExpireAt.isBefore(now)) {
                ids.add(slot.holdId);
            }
        }
    }

    @Override
    public int confirmPaidTherapistSlots(long orderId, long holdId, int serviceEndSlotNo, LocalDateTime now) {
        return confirmPaid(therapistSlots, orderId, holdId, serviceEndSlotNo);
    }

    @Override
    public int confirmPaidBedSlots(long orderId, long holdId, int serviceEndSlotNo, LocalDateTime now) {
        return confirmPaid(bedSlots, orderId, holdId, serviceEndSlotNo);
    }

    private int confirmPaid(Map<String, MutableSlot> slots, long orderId, long holdId, int serviceEnd) {
        Work w = requireWork();
        int n = 0;
        for (MutableSlot slot : slots.values()) {
            if (slot.orderId != null && slot.orderId == orderId
                    && slot.holdId != null && slot.holdId == holdId
                    && SlotStatus.LOCKED.equals(slot.status)) {
                Snapshot snap = slot.snapshot();
                slot.status = slot.slotNo < serviceEnd ? SlotStatus.BOOKED : SlotStatus.BUFFER;
                slot.lockExpireAt = null;
                w.undos.add(() -> slot.restore(snap));
                n++;
            }
        }
        return n;
    }

    @Override
    public int markReleaseLockJobDone(long holdId, LocalDateTime now) {
        Work w = requireWork();
        int n = 0;
        String biz = "hold:" + holdId;
        for (MutableJob job : jobs.values()) {
            if (SlotOccupyService.JOB_RELEASE_LOCK.equals(job.jobType)
                    && biz.equals(job.bizKey)
                    && ("PENDING".equals(job.status) || "RUNNING".equals(job.status))) {
                String prev = job.status;
                job.status = "DONE";
                job.updatedAt = now;
                w.undos.add(() -> job.status = prev);
                n++;
            }
        }
        return n;
    }

    @Override
    public int casOrderStatus(long orderId, String expectedStatus, String toStatus, LocalDateTime now) {
        Work w = requireWork();
        lockRow("O|id|" + orderId, w);
        BookingOrderInsert row = orders.get(orderId);
        if (row == null) {
            return 0;
        }
        String current = orderStatuses.getOrDefault(orderId, row.status());
        if (!expectedStatus.equals(current)) {
            return 0;
        }
        orderStatuses.put(orderId, toStatus);
        w.undos.add(() -> {
            if (expectedStatus.equals(row.status())) {
                orderStatuses.remove(orderId);
            } else {
                orderStatuses.put(orderId, expectedStatus);
            }
        });
        return 1;
    }

    @Override
    public int deleteOccupancyFromSlot(long orderId, int fromSlotNo) {
        Work w = requireWork();
        List<Map.Entry<String, OccupancyInsert>> removed = new ArrayList<>();
        occupancies.entrySet().removeIf(e -> {
            OccupancyInsert row = e.getValue();
            if (row.orderId() != orderId || row.slotNo() < fromSlotNo) {
                return false;
            }
            removed.add(e);
            return true;
        });
        w.undos.add(() -> {
            for (Map.Entry<String, OccupancyInsert> e : removed) {
                occupancies.putIfAbsent(e.getKey(), e.getValue());
            }
        });
        return removed.size();
    }

    @Override
    public int freeOrderTherapistSlotsFrom(long orderId, int fromSlotNo, LocalDateTime now) {
        return freeOrderSlotsFrom(therapistSlots, orderId, fromSlotNo);
    }

    @Override
    public int freeOrderBedSlotsFrom(long orderId, int fromSlotNo, LocalDateTime now) {
        return freeOrderSlotsFrom(bedSlots, orderId, fromSlotNo);
    }

    private int freeOrderSlotsFrom(Map<String, MutableSlot> slots, long orderId, int fromSlotNo) {
        Work w = requireWork();
        int n = 0;
        for (MutableSlot slot : slots.values()) {
            if (slot.orderId != null && slot.orderId == orderId
                    && slot.slotNo >= fromSlotNo
                    && (SlotStatus.LOCKED.equals(slot.status)
                    || SlotStatus.BOOKED.equals(slot.status)
                    || SlotStatus.BUFFER.equals(slot.status))) {
                Snapshot snap = slot.snapshot();
                slot.status = SlotStatus.FREE;
                slot.orderId = null;
                slot.holdId = null;
                slot.lockExpireAt = null;
                w.undos.add(() -> slot.restore(snap));
                n++;
            }
        }
        return n;
    }

    @Override
    public int clearAddOnHold(long orderId, LocalDateTime now) {
        Long prev = addOnHolds.remove(orderId);
        requireWork().undos.add(() -> {
            if (prev != null) {
                addOnHolds.put(orderId, prev);
            }
        });
        return prev == null ? 0 : 1;
    }

    @Override
    public int deleteOccupancyForHoldFromSlot(long holdId, int fromSlotNo) {
        Work w = requireWork();
        List<Map.Entry<String, OccupancyInsert>> removed = new ArrayList<>();
        occupancies.entrySet().removeIf(e -> {
            OccupancyInsert row = e.getValue();
            if (row.holdId() != holdId || row.slotNo() < fromSlotNo) {
                return false;
            }
            removed.add(e);
            return true;
        });
        w.undos.add(() -> {
            for (Map.Entry<String, OccupancyInsert> e : removed) {
                occupancies.putIfAbsent(e.getKey(), e.getValue());
            }
        });
        return removed.size();
    }

    @Override
    public int freeHoldTherapistSlotsFrom(long holdId, int fromSlotNo, LocalDateTime now) {
        return freeHoldFrom(therapistSlots, holdId, fromSlotNo);
    }

    @Override
    public int freeHoldBedSlotsFrom(long holdId, int fromSlotNo, LocalDateTime now) {
        return freeHoldFrom(bedSlots, holdId, fromSlotNo);
    }

    private int freeHoldFrom(Map<String, MutableSlot> slots, long holdId, int fromSlotNo) {
        Work w = requireWork();
        int n = 0;
        for (MutableSlot slot : slots.values()) {
            if (slot.holdId != null && slot.holdId == holdId && slot.slotNo >= fromSlotNo) {
                Snapshot snap = slot.snapshot();
                slot.status = SlotStatus.FREE;
                slot.orderId = null;
                slot.holdId = null;
                slot.lockExpireAt = null;
                w.undos.add(() -> slot.restore(snap));
                n++;
            }
        }
        return n;
    }

    @Override
    public int restoreBufferSlots(
            long orderId, int fromSlotNo, int toExclusive, long mainHoldId, LocalDateTime now) {
        return restoreBuffer(therapistSlots, orderId, fromSlotNo, toExclusive, mainHoldId)
                + restoreBuffer(bedSlots, orderId, fromSlotNo, toExclusive, mainHoldId);
    }

    private int restoreBuffer(
            Map<String, MutableSlot> slots, long orderId, int fromSlotNo, int toExclusive, long mainHoldId) {
        Work w = requireWork();
        int n = 0;
        for (MutableSlot slot : slots.values()) {
            if (slot.orderId != null && slot.orderId == orderId
                    && slot.slotNo >= fromSlotNo && slot.slotNo < toExclusive) {
                Snapshot snap = slot.snapshot();
                slot.status = SlotStatus.BUFFER;
                slot.holdId = mainHoldId;
                slot.lockExpireAt = null;
                w.undos.add(() -> slot.restore(snap));
                n++;
            }
        }
        return n;
    }

    @Override
    public int reassignOccupancyHold(long orderId, int fromSlotNo, int toExclusive, long mainHoldId) {
        Work w = requireWork();
        int n = 0;
        List<Runnable> undo = new ArrayList<>();
        for (Map.Entry<String, OccupancyInsert> e : occupancies.entrySet()) {
            OccupancyInsert row = e.getValue();
            if (row.orderId() != orderId || row.slotNo() < fromSlotNo || row.slotNo() >= toExclusive) {
                continue;
            }
            OccupancyInsert prev = row;
            OccupancyInsert next = new OccupancyInsert(
                    row.id(), row.resourceType(), row.resourceId(), row.slotDate(), row.slotNo(),
                    row.orderId(), mainHoldId, row.createdAt());
            e.setValue(next);
            undo.add(() -> occupancies.put(e.getKey(), prev));
            n++;
        }
        w.undos.add(() -> {
            for (int i = undo.size() - 1; i >= 0; i--) {
                undo.get(i).run();
            }
        });
        return n;
    }

    @Override
    public synchronized int deleteUnpaidAddOnItems(long orderId, int fromSlotNo) {
        List<OrderItemInsert> removed = new ArrayList<>();
        orderItems.removeIf(item -> {
            if (item.orderId() == orderId && "ADD_ON".equals(item.itemType())
                    && item.startSlotNo() >= fromSlotNo) {
                removed.add(item);
                return true;
            }
            return false;
        });
        requireWork().undos.add(() -> {
            synchronized (this) {
                orderItems.addAll(removed);
            }
        });
        return removed.size();
    }

    @Override
    public SlotHoldMeta findHoldSlotMeta(long holdId) {
        for (MutableSlot slot : therapistSlots.values()) {
            if (slot.holdId != null && slot.holdId == holdId) {
                return new SlotHoldMeta(slot.storeId, slot.date);
            }
        }
        for (MutableSlot slot : bedSlots.values()) {
            if (slot.holdId != null && slot.holdId == holdId) {
                return new SlotHoldMeta(slot.storeId, slot.date);
            }
        }
        return null;
    }

    @Override
    public synchronized List<Long> claimDueJobs(String instanceId, LocalDateTime now, int leaseSeconds, int limit) {
        LocalDateTime leaseUntil = now.plusSeconds(leaseSeconds);
        List<MutableJob> due = jobs.values().stream()
                .filter(j -> ("PENDING".equals(j.status) && !j.runAt.isAfter(now))
                        || ("RUNNING".equals(j.status) && j.leaseUntil != null && j.leaseUntil.isBefore(now)))
                .sorted(Comparator.comparing(j -> j.runAt))
                .limit(limit)
                .toList();
        List<Long> ids = new ArrayList<>();
        for (MutableJob job : due) {
            if ("RUNNING".equals(job.status)) {
                job.retryCount++;
            }
            job.status = "RUNNING";
            job.lockedBy = instanceId;
            job.lockedAt = now;
            job.leaseUntil = leaseUntil;
            ids.add(job.id);
        }
        return ids;
    }

    @Override
    public DelayedJobRow findJob(long id) {
        MutableJob job = jobs.get(id);
        return job == null ? null : job.toRow();
    }

    @Override
    public int completeJob(long id, String status, String lastError, LocalDateTime now) {
        MutableJob job = jobs.get(id);
        if (job == null) {
            return 0;
        }
        job.status = status;
        job.lastError = lastError;
        job.updatedAt = now;
        return 1;
    }

    @Override
    public List<OwnedSlotRow> lockTherapistSlots(long therapistId, LocalDate date, List<Integer> slotNos) {
        return lockOwned(therapistSlots, therapistId, date, slotNos, true);
    }

    @Override
    public List<OwnedSlotRow> lockBedSlots(long bedId, LocalDate date, List<Integer> slotNos) {
        return lockOwned(bedSlots, bedId, date, slotNos, false);
    }

    private List<OwnedSlotRow> lockOwned(
            Map<String, MutableSlot> slots,
            long resourceId,
            LocalDate date,
            List<Integer> slotNos,
            boolean therapist
    ) {
        Work w = requireWork();
        List<Integer> ordered = slotNos.stream().sorted().toList();
        List<OwnedSlotRow> locked = new ArrayList<>();
        for (int slotNo : ordered) {
            String key = therapist ? tkey(resourceId, date, slotNo) : bkey(resourceId, date, slotNo);
            lockRow(key, w);
            MutableSlot slot = slots.get(key);
            if (slot != null) {
                locked.add(new OwnedSlotRow(slotNo, slot.status, slot.orderId));
            }
        }
        return locked;
    }

    @Override
    public int updateTherapistSlotDest(
            long therapistId, LocalDate date, int slotNo,
            String expectedStatus, Long expectedOrderId,
            String destStatus, long orderId, long holdId,
            LocalDateTime expireAt, LocalDateTime now) {
        return updateSlotDest(therapistSlots, tkey(therapistId, date, slotNo),
                expectedStatus, expectedOrderId, destStatus, orderId, holdId, expireAt);
    }

    @Override
    public int updateBedSlotDest(
            long bedId, LocalDate date, int slotNo,
            String expectedStatus, Long expectedOrderId,
            String destStatus, long orderId, long holdId,
            LocalDateTime expireAt, LocalDateTime now) {
        return updateSlotDest(bedSlots, bkey(bedId, date, slotNo),
                expectedStatus, expectedOrderId, destStatus, orderId, holdId, expireAt);
    }

    private int updateSlotDest(
            Map<String, MutableSlot> slots,
            String key,
            String expectedStatus,
            Long expectedOrderId,
            String destStatus,
            long orderId,
            long holdId,
            LocalDateTime expireAt
    ) {
        Work w = requireWork();
        MutableSlot slot = slots.get(key);
        if (slot == null || !expectedStatus.equals(slot.status)) {
            return 0;
        }
        if (expectedOrderId == null) {
            if (slot.orderId != null) {
                return 0;
            }
        } else if (!expectedOrderId.equals(slot.orderId)) {
            return 0;
        }
        Snapshot snap = slot.snapshot();
        slot.status = destStatus;
        slot.orderId = orderId;
        slot.holdId = holdId;
        slot.lockExpireAt = expireAt;
        w.undos.add(() -> slot.restore(snap));
        return 1;
    }

    @Override
    public int applyCashAddOn(long orderId, int newEndSlotNo, long addAmountFen, LocalDateTime now) {
        return bumpOrderMoney(orderId, newEndSlotNo, addAmountFen, addAmountFen, false);
    }

    @Override
    public int setAddOnHold(long orderId, long addHoldId, LocalDateTime now) {
        Work w = requireWork();
        Long prev = addOnHolds.put(orderId, addHoldId);
        w.undos.add(() -> {
            if (prev == null) {
                addOnHolds.remove(orderId);
            } else {
                addOnHolds.put(orderId, prev);
            }
        });
        return 1;
    }

    @Override
    public int applyPaidAddOn(long orderId, int newEndSlotNo, long addAmountFen, LocalDateTime now) {
        return bumpOrderMoney(orderId, newEndSlotNo, addAmountFen, addAmountFen, true);
    }

    private int bumpOrderMoney(
            long orderId, int newEndSlotNo, long addPayable, long addPaid, boolean clearHold) {
        Work w = requireWork();
        BookingOrderInsert row = orders.get(orderId);
        if (row == null) {
            return 0;
        }
        Integer prevEnd = endSlotNos.get(orderId);
        Long prevPayable = payableFens.get(orderId);
        Long prevPaid = paidFens.get(orderId);
        Long prevHold = addOnHolds.get(orderId);
        long currentPayable = prevPayable == null ? row.payableFen() : prevPayable;
        long currentPaid = prevPaid == null ? 0L : prevPaid;
        endSlotNos.put(orderId, newEndSlotNo);
        payableFens.put(orderId, currentPayable + addPayable);
        paidFens.put(orderId, currentPaid + addPaid);
        if (clearHold) {
            addOnHolds.remove(orderId);
        }
        w.undos.add(() -> {
            restoreOverlay(endSlotNos, orderId, prevEnd);
            restoreOverlay(payableFens, orderId, prevPayable);
            restoreOverlay(paidFens, orderId, prevPaid);
            if (clearHold) {
                if (prevHold == null) {
                    addOnHolds.remove(orderId);
                } else {
                    addOnHolds.put(orderId, prevHold);
                }
            }
        });
        return 1;
    }

    private static <V> void restoreOverlay(Map<Long, V> map, long orderId, V prev) {
        if (prev == null) {
            map.remove(orderId);
        } else {
            map.put(orderId, prev);
        }
    }

    @Override
    public int markReleaseAddonJobDone(long addHoldId, LocalDateTime now) {
        Work w = requireWork();
        int n = 0;
        String biz = "hold:" + addHoldId;
        for (MutableJob job : jobs.values()) {
            if (SlotOccupyService.JOB_RELEASE_ADDON.equals(job.jobType)
                    && biz.equals(job.bizKey)
                    && ("PENDING".equals(job.status) || "RUNNING".equals(job.status))) {
                String prev = job.status;
                job.status = "DONE";
                job.updatedAt = now;
                w.undos.add(() -> job.status = prev);
                n++;
            }
        }
        return n;
    }

    @Override
    public synchronized OrderItemInsert findLatestAddOnItem(long orderId) {
        OrderItemInsert latest = null;
        for (OrderItemInsert item : orderItems) {
            if (item.orderId() == orderId && "ADD_ON".equals(item.itemType())) {
                if (latest == null || item.id() > latest.id()) {
                    latest = item;
                }
            }
        }
        return latest;
    }

    @Override
    public void insertCashPayment(long id, String paymentNo, long orderId, long amountFen, LocalDateTime now) {
    }

    public long paidFen(long orderId) {
        return paidFens.getOrDefault(orderId, 0L);
    }

    void setOrderStatus(long orderId, String status) {
        orderStatuses.put(orderId, status);
    }

    public void setAddOnHoldId(long orderId, Long addOnHoldId) {
        if (addOnHoldId == null) {
            addOnHolds.remove(orderId);
        } else {
            addOnHolds.put(orderId, addOnHoldId);
        }
    }

    /** Test helper: lock one extra tail slot as an unpaid add-on hold. */
    public void plantAddOnTail(long orderId, long therapistId, long bedId, LocalDate date,
                               int slotNo, long addHoldId, LocalDateTime now) {
        setAddOnHoldId(orderId, addHoldId);
        MutableSlot t = therapistSlots.get(tkey(therapistId, date, slotNo));
        MutableSlot b = bedSlots.get(bkey(bedId, date, slotNo));
        if (t != null) {
            t.status = SlotStatus.LOCKED;
            t.orderId = orderId;
            t.holdId = addHoldId;
            t.lockExpireAt = now.plusMinutes(15);
        }
        if (b != null) {
            b.status = SlotStatus.LOCKED;
            b.orderId = orderId;
            b.holdId = addHoldId;
            b.lockExpireAt = now.plusMinutes(15);
        }
        occupancies.put(okey(ResourceType.THERAPIST, therapistId, date, slotNo),
                new OccupancyInsert(addHoldId + 1, ResourceType.THERAPIST, therapistId, date, slotNo,
                        orderId, addHoldId, now));
        occupancies.put(okey(ResourceType.BED, bedId, date, slotNo),
                new OccupancyInsert(addHoldId + 2, ResourceType.BED, bedId, date, slotNo,
                        orderId, addHoldId, now));
        orderItems.add(new OrderItemInsert(
                addHoldId + 3, orderId, "ADD_ON", 0L, "加钟", 15, 0, 1, 0, 0, slotNo, slotNo + 1, now));
    }

    MutableJob job(long id) {
        return jobs.get(id);
    }

    public MutableJob jobByHold(long holdId) {
        String biz = "hold:" + holdId;
        return jobs.values().stream()
                .filter(j -> biz.equals(j.bizKey))
                .findFirst()
                .orElse(null);
    }

    public void expireHold(long holdId, LocalDateTime expireAt) {
        for (MutableSlot slot : therapistSlots.values()) {
            if (slot.holdId != null && slot.holdId == holdId) {
                slot.lockExpireAt = expireAt;
            }
        }
        for (MutableSlot slot : bedSlots.values()) {
            if (slot.holdId != null && slot.holdId == holdId) {
                slot.lockExpireAt = expireAt;
            }
        }
    }

    private BookingOrderRef toRef(BookingOrderInsert row) {
        return new BookingOrderRef(
                row.id(), row.orderNo(), row.holdId(), row.bedId(), row.roomId(),
                orderStatuses.getOrDefault(row.id(), row.status()),
                row.lockExpireAt(), payableFens.getOrDefault(row.id(), row.payableFen()),
                row.startSlotNo(), endSlotNos.getOrDefault(row.id(), row.endSlotNo()), row.bufferSlots(),
                addOnHolds.get(row.id()), row.storeId(), row.serviceDate(),
                row.customerId(), row.therapistId());
    }

    private MutableSlot slotFor(OccupancyInsert row) {
        if (ResourceType.THERAPIST.equals(row.resourceType())) {
            return therapistSlots.get(tkey(row.resourceId(), row.slotDate(), row.slotNo()));
        }
        return bedSlots.get(bkey(row.resourceId(), row.slotDate(), row.slotNo()));
    }

    void seedProject(ProjectRef project) {
        projects.put(project.id(), project);
    }

    void seedTherapist(TherapistRef therapist) {
        therapists.put(therapist.id(), therapist);
    }

    void seedBed(BedRef bed) {
        beds.put(bed.id(), bed);
    }

    void seedStorePrice(long storeId, long projectId, long priceFen) {
        storePrices.put(storeId + "|" + projectId, priceFen);
    }

    void seedSlotOverride(long therapistId, LocalDate date, int slotNo, long priceFen) {
        slotOverrides.put(tkey(therapistId, date, slotNo), priceFen);
    }

    void seedTherapistSlots(long therapistId, long storeId, LocalDate date, int fromInclusive, int toExclusive, String status) {
        for (int slot = fromInclusive; slot < toExclusive; slot++) {
            therapistSlots.put(tkey(therapistId, date, slot),
                    new MutableSlot(therapistId, storeId, date, slot, status));
        }
    }

    void seedBedSlots(long bedId, long storeId, LocalDate date, int fromInclusive, int toExclusive, String status) {
        for (int slot = fromInclusive; slot < toExclusive; slot++) {
            bedSlots.put(bkey(bedId, date, slot),
                    new MutableSlot(bedId, storeId, date, slot, status));
        }
    }

    public MutableSlot therapistSlot(long therapistId, LocalDate date, int slotNo) {
        return therapistSlots.get(tkey(therapistId, date, slotNo));
    }

    public List<BookingOrderRef> listTherapistDayOrders(long therapistId, LocalDate date) {
        return orders.values().stream()
                .filter(row -> row.therapistId() == therapistId && date.equals(row.serviceDate()))
                .map(this::toRef)
                .sorted(Comparator.comparingInt(BookingOrderRef::startSlotNo).thenComparingLong(BookingOrderRef::id))
                .toList();
    }

    public List<TherapistSlotGlance> listTherapistDaySlots(long therapistId, LocalDate date) {
        return therapistSlots.values().stream()
                .filter(slot -> slot.resourceId == therapistId && date.equals(slot.date))
                .sorted(Comparator.comparingInt(slot -> slot.slotNo))
                .map(slot -> new TherapistSlotGlance(slot.slotNo, slot.status, slot.orderId))
                .toList();
    }

    public String firstProjectName(long orderId) {
        synchronized (this) {
            return orderItems.stream()
                    .filter(item -> item.orderId() == orderId && "PROJECT".equals(item.itemType()))
                    .map(OrderItemInsert::projectName)
                    .findFirst()
                    .orElse(null);
        }
    }

    public long countCompletedForCustomer(long customerId) {
        return orders.values().stream()
                .map(this::toRef)
                .filter(row -> row.customerId() == customerId)
                .filter(row -> "COMPLETED".equals(row.status()) || "REVIEWED".equals(row.status()))
                .count();
    }

    public record TherapistSlotGlance(int slotNo, String status, Long orderId) {
    }

    MutableSlot bedSlot(long bedId, LocalDate date, int slotNo) {
        return bedSlots.get(bkey(bedId, date, slotNo));
    }

    IdemState idem(String scope, String requestId) {
        return idempotency.get(ikey(scope, requestId));
    }

    static String tkey(long therapistId, LocalDate date, int slotNo) {
        return "T|" + therapistId + "|" + date + "|" + slotNo;
    }

    static String bkey(long bedId, LocalDate date, int slotNo) {
        return "B|" + bedId + "|" + date + "|" + slotNo;
    }

    static String okey(String type, long resourceId, LocalDate date, int slotNo) {
        return type + "|" + resourceId + "|" + date + "|" + slotNo;
    }

    private static String ikey(String scope, String requestId) {
        return scope + "|" + requestId;
    }

    private ReentrantLock lockRow(String key, Work w) {
        ReentrantLock lock = rowLocks.computeIfAbsent(key, k -> new ReentrantLock());
        if (!lock.isHeldByCurrentThread()) {
            if (key.startsWith("T|") || key.startsWith("B|")) {
                slotPinAttempts.add(key);
            }
            lock.lock();
            w.locks.add(lock);
        }
        return lock;
    }

    private Work requireWork() {
        Work w = work.get();
        if (w == null) {
            w = new Work();
            work.set(w);
        }
        return w;
    }

    public static final class MutableSlot {
        final long resourceId;
        final long storeId;
        final LocalDate date;
        final int slotNo;
        public volatile String status;
        volatile Long orderId;
        public volatile Long holdId;
        volatile LocalDateTime lockExpireAt;

        MutableSlot(long resourceId, long storeId, LocalDate date, int slotNo, String status) {
            this.resourceId = resourceId;
            this.storeId = storeId;
            this.date = date;
            this.slotNo = slotNo;
            this.status = status;
        }

        Snapshot snapshot() {
            return new Snapshot(status, orderId, holdId, lockExpireAt);
        }

        void restore(Snapshot saved) {
            status = saved.status;
            orderId = saved.orderId;
            holdId = saved.holdId;
            lockExpireAt = saved.lockExpireAt;
        }
    }

    private record Snapshot(String status, Long orderId, Long holdId, LocalDateTime lockExpireAt) {
    }

    public static final class MutableJob {
        public final long id;
        final String jobType;
        final String bizKey;
        final String payload;
        public volatile LocalDateTime runAt;
        public volatile String status;
        volatile String lockedBy;
        volatile LocalDateTime lockedAt;
        volatile LocalDateTime leaseUntil;
        volatile int retryCount;
        public volatile String lastError;
        volatile LocalDateTime updatedAt;

        MutableJob(
                long id, String jobType, String bizKey, String payload, LocalDateTime runAt, String status) {
            this.id = id;
            this.jobType = jobType;
            this.bizKey = bizKey;
            this.payload = payload;
            this.runAt = runAt;
            this.status = status;
        }

        static MutableJob from(DelayedJobInsert row) {
            return new MutableJob(row.id(), row.jobType(), row.bizKey(), row.payload(), row.runAt(), row.status());
        }

        DelayedJobRow toRow() {
            return new DelayedJobRow(
                    id, jobType, bizKey, payload, runAt, status, lockedBy, leaseUntil, retryCount, lastError);
        }
    }

    static final class IdemState {
        final long id;
        final String scope;
        final String requestId;
        volatile String status;
        volatile int version;
        volatile String lockedBy;
        volatile String responseBody;
        volatile LocalDateTime expireAt;
        volatile LocalDateTime updatedAt;

        IdemState(IdemInsert row) {
            this.id = row.id();
            this.scope = row.scope();
            this.requestId = row.requestId();
            this.status = row.status();
            this.version = row.version();
            this.lockedBy = row.lockedBy();
            this.expireAt = row.expireAt();
            this.updatedAt = row.updatedAt();
        }

        IdemRow toRow() {
            return new IdemRow(status, version, expireAt, responseBody);
        }
    }

    @Override
    public List<SlotRow> listTherapistSlots(long therapistId, LocalDate date, List<Integer> slotNos) {
        List<SlotRow> rows = new ArrayList<>();
        for (int slotNo : slotNos.stream().sorted().toList()) {
            MutableSlot slot = therapistSlots.get(tkey(therapistId, date, slotNo));
            if (slot != null) {
                rows.add(new SlotRow(slotNo, slot.status));
            }
        }
        return rows;
    }

    @Override
    public int assignTherapistSlot(
            long therapistId, LocalDate date, int slotNo, String status,
            long orderId, long holdId, LocalDateTime lockExpireAt, LocalDateTime now) {
        Work w = requireWork();
        MutableSlot slot = therapistSlots.get(tkey(therapistId, date, slotNo));
        if (slot == null || !SlotStatus.FREE.equals(slot.status)) {
            return 0;
        }
        Snapshot snap = slot.snapshot();
        slot.status = status;
        slot.orderId = orderId;
        slot.holdId = holdId;
        slot.lockExpireAt = lockExpireAt;
        w.undos.add(() -> slot.restore(snap));
        return 1;
    }

    @Override
    public int deleteTherapistOccupancy(long therapistId, LocalDate date, List<Integer> slotNos) {
        Work w = requireWork();
        List<Map.Entry<String, OccupancyInsert>> removed = new ArrayList<>();
        for (int slotNo : slotNos) {
            String key = okey(ResourceType.THERAPIST, therapistId, date, slotNo);
            OccupancyInsert prev = occupancies.remove(key);
            if (prev != null) {
                removed.add(Map.entry(key, prev));
            }
        }
        w.undos.add(() -> {
            for (Map.Entry<String, OccupancyInsert> e : removed) {
                occupancies.putIfAbsent(e.getKey(), e.getValue());
            }
        });
        return removed.size();
    }

    @Override
    public int freeTherapistSlots(long therapistId, LocalDate date, List<Integer> slotNos, LocalDateTime now) {
        Work w = requireWork();
        int n = 0;
        for (int slotNo : slotNos) {
            MutableSlot slot = therapistSlots.get(tkey(therapistId, date, slotNo));
            if (slot == null) {
                continue;
            }
            Snapshot snap = slot.snapshot();
            slot.status = SlotStatus.FREE;
            slot.orderId = null;
            slot.holdId = null;
            slot.lockExpireAt = null;
            w.undos.add(() -> slot.restore(snap));
            n++;
        }
        return n;
    }

    @Override
    public int updateTherapist(long orderId, long newTherapistId, long newHomeStoreId, LocalDateTime now) {
        BookingOrderInsert prev = orders.get(orderId);
        if (prev == null) {
            return 0;
        }
        BookingOrderInsert next = new BookingOrderInsert(
                prev.id(), prev.orderNo(), prev.requestId(), prev.holdId(),
                prev.customerId(), prev.storeId(), newTherapistId, newHomeStoreId,
                prev.bedId(), prev.roomId(), prev.status(), prev.source(),
                prev.serviceDate(), prev.startSlotNo(), prev.endSlotNo(), prev.bufferSlots(),
                prev.originPriceFen(), prev.payableFen(), prev.lockExpireAt(), prev.createdAt());
        orders.put(orderId, next);
        ordersByRequest.put(next.requestId(), next);
        requireWork().undos.add(() -> {
            orders.put(orderId, prev);
            ordersByRequest.put(prev.requestId(), prev);
        });
        return 1;
    }

    private static final class Work {
        final List<Runnable> undos = new ArrayList<>();
        final List<ReentrantLock> locks = new ArrayList<>();
        int depth;

        void unlockAll() {
            for (int i = locks.size() - 1; i >= 0; i--) {
                ReentrantLock lock = locks.get(i);
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
            locks.clear();
        }
    }


    public final List<OrderChangeLogInsert> changeLogs = new ArrayList<>();

    public List<OrderChangeLogInsert> listChangeLogs() {
        synchronized (this) {
            return List.copyOf(changeLogs);
        }
    }

    @Override
    public String peekSlotStatus(String resourceType, long resourceId, LocalDate date, int slotNo) {
        MutableSlot slot = ResourceType.THERAPIST.equals(resourceType)
                ? therapistSlots.get(tkey(resourceId, date, slotNo))
                : bedSlots.get(bkey(resourceId, date, slotNo));
        return slot == null ? null : slot.status;
    }

    @Override
    public List<RescheduleSlotRow> lockRescheduleSlots(List<RescheduleSlotKey> keys) {
        Work w = requireWork();
        List<RescheduleSlotRow> rows = new ArrayList<>();
        if (keys == null || keys.isEmpty()) {
            return rows;
        }
        List<RescheduleSlotKey> ordered = keys.stream().sorted().distinct().toList();
        for (RescheduleSlotKey key : ordered) {
            String rowKey = ResourceType.THERAPIST.equals(key.resourceType())
                    ? tkey(key.resourceId(), key.slotDate(), key.slotNo())
                    : bkey(key.resourceId(), key.slotDate(), key.slotNo());
            lockRow(rowKey, w);
            MutableSlot slot = ResourceType.THERAPIST.equals(key.resourceType())
                    ? therapistSlots.get(rowKey) : bedSlots.get(rowKey);
            if (slot != null) {
                rows.add(new RescheduleSlotRow(
                        key.resourceType(), key.resourceId(), key.slotDate(), key.slotNo(),
                        slot.status, slot.orderId, slot.storeId));
            }
        }
        return rows;
    }

    @Override
    public int applyRescheduleAcquire(
            List<RescheduleAcquire> acquire, long orderId, long holdId, LocalDateTime now) {
        if (acquire == null || acquire.isEmpty()) {
            return 0;
        }
        Work w = requireWork();
        int n = 0;
        for (RescheduleAcquire item : acquire) {
            RescheduleSlotKey key = item.key();
            MutableSlot slot = slotOf(key);
            if (slot == null) {
                continue;
            }
            Snapshot snap = slot.snapshot();
            slot.status = item.destStatus();
            slot.orderId = orderId;
            slot.holdId = holdId;
            slot.lockExpireAt = null;
            w.undos.add(() -> slot.restore(snap));
            n++;
        }
        return n;
    }

    @Override
    public int deleteRescheduleOccupancy(List<RescheduleSlotKey> release, long orderId) {
        if (release == null || release.isEmpty()) {
            return 0;
        }
        Work w = requireWork();
        List<Map.Entry<String, OccupancyInsert>> removed = new ArrayList<>();
        for (RescheduleSlotKey key : release) {
            String okey = okey(key.resourceType(), key.resourceId(), key.slotDate(), key.slotNo());
            OccupancyInsert row = occupancies.get(okey);
            if (row == null || row.orderId() != orderId) {
                continue;
            }
            occupancies.remove(okey);
            removed.add(Map.entry(okey, row));
        }
        w.undos.add(() -> {
            for (Map.Entry<String, OccupancyInsert> e : removed) {
                occupancies.putIfAbsent(e.getKey(), e.getValue());
            }
        });
        return removed.size();
    }

    @Override
    public int freeRescheduleSlots(List<RescheduleSlotKey> release, long orderId, LocalDateTime now) {
        if (release == null || release.isEmpty()) {
            return 0;
        }
        Work w = requireWork();
        int n = 0;
        for (RescheduleSlotKey key : release) {
            MutableSlot slot = slotOf(key);
            if (slot == null || slot.orderId == null || slot.orderId != orderId) {
                continue;
            }
            Snapshot snap = slot.snapshot();
            slot.status = SlotStatus.FREE;
            slot.orderId = null;
            slot.holdId = null;
            slot.lockExpireAt = null;
            w.undos.add(() -> slot.restore(snap));
            n++;
        }
        return n;
    }

    @Override
    public int reholdRescheduleKeep(List<RescheduleAcquire> keep, long orderId, long newHold, LocalDateTime now) {
        if (keep == null || keep.isEmpty()) {
            return 0;
        }
        Work w = requireWork();
        int n = 0;
        List<Runnable> undo = new ArrayList<>();
        for (RescheduleAcquire item : keep) {
            RescheduleSlotKey key = item.key();
            MutableSlot slot = slotOf(key);
            if (slot != null && slot.orderId != null && slot.orderId == orderId) {
                Snapshot snap = slot.snapshot();
                slot.holdId = newHold;
                slot.status = item.destStatus();
                undo.add(() -> slot.restore(snap));
                n++;
            }
            String okey = okey(key.resourceType(), key.resourceId(), key.slotDate(), key.slotNo());
            OccupancyInsert row = occupancies.get(okey);
            if (row != null && row.orderId() == orderId) {
                OccupancyInsert next = new OccupancyInsert(
                        row.id(), row.resourceType(), row.resourceId(), row.slotDate(), row.slotNo(),
                        row.orderId(), newHold, row.createdAt());
                occupancies.put(okey, next);
                undo.add(() -> occupancies.put(okey, row));
            }
        }
        w.undos.add(() -> {
            for (int i = undo.size() - 1; i >= 0; i--) {
                undo.get(i).run();
            }
        });
        return n;
    }

    @Override
    public int updateOrderForReschedule(
            long orderId,
            long holdId,
            long therapistId,
            Long therapistHomeStoreId,
            LocalDate serviceDate,
            int startSlotNo,
            int endSlotNo,
            long bedId,
            long roomId,
            LocalDateTime now) {
        BookingOrderInsert prev = orders.get(orderId);
        if (prev == null) {
            return 0;
        }
        long home = therapistHomeStoreId == null ? prev.therapistHomeStoreId() : therapistHomeStoreId;
        BookingOrderInsert next = new BookingOrderInsert(
                prev.id(), prev.orderNo(), prev.requestId(), holdId,
                prev.customerId(), prev.storeId(), therapistId, home,
                bedId, roomId, prev.status(), prev.source(),
                serviceDate, startSlotNo, endSlotNo, prev.bufferSlots(),
                prev.originPriceFen(), prev.payableFen(), null, prev.createdAt());
        orders.put(orderId, next);
        if (prev.requestId() != null) {
            ordersByRequest.put(prev.requestId(), next);
        }
        requireWork().undos.add(() -> {
            orders.put(orderId, prev);
            if (prev.requestId() != null) {
                ordersByRequest.put(prev.requestId(), prev);
            }
        });
        return 1;
    }

    @Override
    public synchronized int updateProjectItemWindow(long orderId, int startSlotNo, int endSlotNo) {
        List<OrderItemInsert> prev = new ArrayList<>(orderItems);
        boolean changed = false;
        for (int i = 0; i < orderItems.size(); i++) {
            OrderItemInsert item = orderItems.get(i);
            if (item.orderId() == orderId && "PROJECT".equals(item.itemType())) {
                orderItems.set(i, new OrderItemInsert(
                        item.id(), item.orderId(), item.itemType(), item.projectId(), item.projectName(),
                        item.durationMinutes(), item.bufferMinutes(), item.quantity(),
                        item.unitPriceFen(), item.amountFen(), startSlotNo, endSlotNo, item.createdAt()));
                changed = true;
            }
        }
        if (!changed) {
            return 0;
        }
        requireWork().undos.add(() -> {
            synchronized (this) {
                orderItems.clear();
                orderItems.addAll(prev);
            }
        });
        return 1;
    }

    @Override
    public synchronized void insertOrderChangeLog(OrderChangeLogInsert row) {
        changeLogs.add(row);
        requireWork().undos.add(() -> {
            synchronized (this) {
                changeLogs.remove(row);
            }
        });
    }

    @Override
    public synchronized OrderItemInsert findProjectItem(long orderId) {
        return orderItems.stream()
                .filter(item -> item.orderId() == orderId && "PROJECT".equals(item.itemType()))
                .findFirst()
                .orElse(null);
    }

    private MutableSlot slotOf(RescheduleSlotKey key) {
        return ResourceType.THERAPIST.equals(key.resourceType())
                ? therapistSlots.get(tkey(key.resourceId(), key.slotDate(), key.slotNo()))
                : bedSlots.get(bkey(key.resourceId(), key.slotDate(), key.slotNo()));
    }
}
