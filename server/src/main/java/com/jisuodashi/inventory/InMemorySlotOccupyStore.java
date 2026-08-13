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
import com.jisuodashi.inventory.SlotOccupyStore.OrderItemInsert;
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
    final List<OrderItemInsert> orderItems = new ArrayList<>();
    final List<DelayedJobInsert> delayedJobs = new ArrayList<>();
    final Map<Long, MutableJob> jobs = new ConcurrentHashMap<>();
    final Map<Long, String> orderStatuses = new ConcurrentHashMap<>();
    final Map<Long, Long> addOnHolds = new ConcurrentHashMap<>();
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
        idempotency.clear();
        slotPinAttempts.clear();
        occupancyInserts.set(0);
        bedOccupancyInserts.set(0);
        therapistSlots.clear();
        bedSlots.clear();
        seedDemoCalendar();
    }

    void seedDemoCatalog() {
        seedProject(new ProjectRef(DemoCatalogIds.PROJECT_P60, "全身推拿放松", 60, 15, 19800));
        seedProject(new ProjectRef(DemoCatalogIds.PROJECT_P45, "肩颈专项疏通", 45, 15, 12800));
        seedProject(new ProjectRef(DemoCatalogIds.PROJECT_P90, "腰背深层理筋", 90, 15, 26800));
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
        BookingOrderInsert row = orders.get(orderId);
        return row == null ? null : toRef(row);
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

    void setOrderStatus(long orderId, String status) {
        orderStatuses.put(orderId, status);
    }

    void setAddOnHoldId(long orderId, Long addOnHoldId) {
        if (addOnHoldId == null) {
            addOnHolds.remove(orderId);
        } else {
            addOnHolds.put(orderId, addOnHoldId);
        }
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

    void expireHold(long holdId, LocalDateTime expireAt) {
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
                row.lockExpireAt(), row.payableFen(),
                row.startSlotNo(), row.endSlotNo(), row.bufferSlots(),
                addOnHolds.get(row.id()), row.storeId(), row.serviceDate());
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
        volatile Long holdId;
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
        final long id;
        final String jobType;
        final String bizKey;
        final String payload;
        volatile LocalDateTime runAt;
        public volatile String status;
        volatile String lockedBy;
        volatile LocalDateTime lockedAt;
        volatile LocalDateTime leaseUntil;
        volatile int retryCount;
        volatile String lastError;
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
}
