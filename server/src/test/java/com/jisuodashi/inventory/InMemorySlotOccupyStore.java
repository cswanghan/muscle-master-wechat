package com.jisuodashi.inventory;

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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory CAS + ordered row locks. H2 cannot run V1 MySQL DDL, so lockNew is tested here.
 */
final class InMemorySlotOccupyStore implements SlotOccupyStore {

    final Map<Long, ProjectRef> projects = new ConcurrentHashMap<>();
    final Map<Long, TherapistRef> therapists = new ConcurrentHashMap<>();
    final Map<Long, BedRef> beds = new ConcurrentHashMap<>();
    final Map<String, MutableSlot> therapistSlots = new ConcurrentHashMap<>();
    final Map<String, MutableSlot> bedSlots = new ConcurrentHashMap<>();
    final Map<String, OccupancyInsert> occupancies = new ConcurrentHashMap<>();
    final Map<String, IdemState> idempotency = new ConcurrentHashMap<>();
    final Map<Long, BookingOrderInsert> orders = new ConcurrentHashMap<>();
    final Map<String, BookingOrderInsert> ordersByRequest = new ConcurrentHashMap<>();
    final List<OrderItemInsert> orderItems = new ArrayList<>();
    final List<DelayedJobInsert> delayedJobs = new ArrayList<>();

    private final ConcurrentHashMap<String, ReentrantLock> rowLocks = new ConcurrentHashMap<>();
    private final ThreadLocal<Work> work = new ThreadLocal<>();

    @Override
    public void beginWork() {
        work.set(new Work());
    }

    @Override
    public void commitWork() {
        Work w = work.get();
        if (w != null) {
            w.unlockAll();
        }
        work.remove();
    }

    @Override
    public void rollbackWork() {
        Work w = work.get();
        if (w != null) {
            for (int i = w.undos.size() - 1; i >= 0; i--) {
                w.undos.get(i).run();
            }
            w.unlockAll();
        }
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
        return new BookingOrderRef(
                row.id(), row.orderNo(), row.holdId(), row.bedId(), row.roomId(),
                row.status(), row.lockExpireAt(), row.payableFen(),
                row.startSlotNo(), row.endSlotNo(), row.bufferSlots());
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
            long therapistId, LocalDate date, List<Integer> slotNos, int bufferFrom,
            long orderId, long holdId, LocalDateTime expireAt, LocalDateTime now) {
        return casLock(therapistSlots, true, therapistId, date, slotNos, bufferFrom, orderId, holdId, expireAt);
    }

    @Override
    public int casLockBedSlots(
            long bedId, LocalDate date, List<Integer> slotNos, int bufferFrom,
            long orderId, long holdId, LocalDateTime expireAt, LocalDateTime now) {
        return casLock(bedSlots, false, bedId, date, slotNos, bufferFrom, orderId, holdId, expireAt);
    }

    private int casLock(
            Map<String, MutableSlot> slots,
            boolean therapist,
            long resourceId,
            LocalDate date,
            List<Integer> slotNos,
            int bufferFrom,
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
            String dest = slotNo >= bufferFrom ? SlotStatus.BUFFER : SlotStatus.LOCKED;
            Snapshot snap = slot.snapshot();
            slot.status = dest;
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
        String key = okey(row.resourceType(), row.resourceId(), row.slotDate(), row.slotNo());
        OccupancyInsert prev = occupancies.putIfAbsent(key, row);
        if (prev != null) {
            throw new DuplicateOccupancyException("occupancy exists " + key);
        }
        requireWork().undos.add(() -> occupancies.remove(key, row));
    }

    @Override
    public void revertBedHold(long bedId, long holdId, LocalDateTime now) {
        Work w = requireWork();
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
        requireWork().undos.add(() -> {
            synchronized (this) {
                delayedJobs.remove(row);
            }
        });
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

    MutableSlot therapistSlot(long therapistId, LocalDate date, int slotNo) {
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

    static final class MutableSlot {
        final long resourceId;
        final long storeId;
        final LocalDate date;
        final int slotNo;
        volatile String status;
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
