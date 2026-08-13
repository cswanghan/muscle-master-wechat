package com.jisuodashi.inventory.persist;

import com.jisuodashi.inventory.DelayedJobStore.DelayedJobRow;
import com.jisuodashi.inventory.DuplicateOccupancyException;
import com.jisuodashi.inventory.SlotOccupyStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Repository
@org.springframework.context.annotation.Profile("!dev")
public class MybatisSlotOccupyStore implements SlotOccupyStore {

    private final InventoryOccupyMapper mapper;

    public MybatisSlotOccupyStore(InventoryOccupyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void beginWork() {
    }

    @Override
    public void commitWork() {
    }

    @Override
    public void rollbackWork() {
    }

    @Override
    public ProjectRef loadProject(long projectId) {
        return mapper.loadProject(projectId);
    }

    @Override
    public TherapistRef loadTherapist(long therapistId) {
        return mapper.loadTherapist(therapistId);
    }

    @Override
    public List<BedRef> listBeds(long storeId) {
        return mapper.listBeds(storeId);
    }

    @Override
    public boolean insertIdempotency(IdemInsert row) {
        try {
            return mapper.insertIdempotency(
                    row.id(), row.scope(), row.requestId(), row.status(), row.version(),
                    row.lockedBy(), row.createdAt(), row.updatedAt(), row.expireAt()) > 0;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    @Override
    public IdemRow lockIdempotency(String scope, String requestId) {
        return mapper.lockIdempotency(scope, requestId);
    }

    @Override
    public int takeoverIdempotency(
            String scope, String requestId, int expectedVersion,
            LocalDateTime expireAt, LocalDateTime now, String lockedBy) {
        return mapper.takeoverIdempotency(scope, requestId, expectedVersion, expireAt, now, lockedBy);
    }

    @Override
    public BookingOrderRef findOrderByRequestId(String requestId) {
        return mapper.findOrderByRequestId(requestId);
    }

    @Override
    public int finishIdempotent(String scope, String requestId, int version, String responseBody, LocalDateTime now) {
        return mapper.finishIdempotent(scope, requestId, version, responseBody, now);
    }

    @Override
    public Long loadStoreProjectPrice(long storeId, long projectId) {
        return mapper.loadStoreProjectPrice(storeId, projectId);
    }

    @Override
    public Long loadSlotPriceOverride(long therapistId, LocalDate date, int startSlotNo) {
        return mapper.loadSlotPriceOverride(therapistId, date, startSlotNo);
    }

    @Override
    public List<SlotRow> lockFreeTherapistSlots(long therapistId, LocalDate date, List<Integer> slotNos) {
        List<Long> ids = mapper.findFreeTherapistSlotIds(therapistId, date, csv(slotNos));
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return mapper.lockTherapistSlotsByIds(csvLongs(ids));
    }

    @Override
    public List<SlotRow> lockFreeBedSlots(long bedId, LocalDate date, List<Integer> slotNos) {
        List<Long> ids = mapper.findFreeBedSlotIds(bedId, date, csv(slotNos));
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return mapper.lockBedSlotsByIds(csvLongs(ids));
    }

    @Override
    public boolean occupancyExists(String resourceType, long resourceId, LocalDate date, List<Integer> slotNos) {
        return mapper.occupancyExists(resourceType, resourceId, date, csv(slotNos));
    }

    @Override
    public int casLockTherapistSlots(
            long therapistId, LocalDate date, List<Integer> slotNos,
            long orderId, long holdId, LocalDateTime expireAt, LocalDateTime now) {
        return mapper.casLockTherapistSlots(therapistId, date, csv(slotNos), orderId, holdId, expireAt, now);
    }

    @Override
    public int casLockBedSlots(
            long bedId, LocalDate date, List<Integer> slotNos,
            long orderId, long holdId, LocalDateTime expireAt, LocalDateTime now) {
        return mapper.casLockBedSlots(bedId, date, csv(slotNos), orderId, holdId, expireAt, now);
    }

    @Override
    public void insertOccupancy(OccupancyInsert row) {
        try {
            mapper.insertOccupancy(
                    row.id(), row.resourceType(), row.resourceId(), row.slotDate(), row.slotNo(),
                    row.orderId(), row.holdId(), row.createdAt());
        } catch (DuplicateKeyException ex) {
            throw new DuplicateOccupancyException(
                    "occupancy exists " + row.resourceType() + "/" + row.resourceId() + "/" + row.slotNo(), ex);
        }
    }

    @Override
    public void revertBedHold(long bedId, long holdId, LocalDateTime now) {
        mapper.deleteBedOccupancy(bedId, holdId);
        mapper.revertBedHold(bedId, holdId, now);
    }

    @Override
    public void insertOrder(BookingOrderInsert row) {
        mapper.insertOrder(
                row.id(), row.orderNo(), row.requestId(), row.holdId(),
                row.customerId(), row.storeId(), row.therapistId(), row.therapistHomeStoreId(),
                row.bedId(), row.roomId(), row.status(), row.source(),
                row.serviceDate(), row.startSlotNo(), row.endSlotNo(), row.bufferSlots(),
                row.originPriceFen(), row.payableFen(), row.lockExpireAt(), row.createdAt());
    }

    @Override
    public void insertOrderItem(OrderItemInsert row) {
        mapper.insertOrderItem(
                row.id(), row.orderId(), row.itemType(), row.projectId(), row.projectName(),
                row.durationMinutes(), row.bufferMinutes(), row.quantity(),
                row.unitPriceFen(), row.amountFen(), row.startSlotNo(), row.endSlotNo(), row.createdAt());
    }

    @Override
    public void insertDelayedJob(DelayedJobInsert row) {
        mapper.insertDelayedJob(
                row.id(), row.jobType(), row.bizKey(), row.payload(), row.runAt(), row.status(), row.createdAt());
    }

    @Override
    public BookingOrderRef findOrderByHoldId(long holdId) {
        return mapper.findOrderByHoldId(holdId);
    }

    @Override
    public BookingOrderRef findOrderByAddOnHoldId(long holdId) {
        return mapper.findOrderByAddOnHoldId(holdId);
    }

    @Override
    public BookingOrderRef lockOrderByHoldId(long holdId) {
        return mapper.lockOrderByHoldId(holdId);
    }

    @Override
    public BookingOrderRef lockOrderById(long orderId) {
        return mapper.lockOrderById(orderId);
    }

    @Override
    public int deleteOccupancyForLockedHold(long holdId) {
        return mapper.deleteOccupancyForLockedTherapist(holdId) + mapper.deleteOccupancyForLockedBed(holdId);
    }

    @Override
    public int deleteOccupancyByHold(long holdId) {
        return mapper.deleteOccupancyByHold(holdId);
    }

    @Override
    public int freeLockedTherapistSlots(long holdId, LocalDateTime now) {
        return mapper.freeLockedTherapistSlots(holdId, now);
    }

    @Override
    public int freeLockedBedSlots(long holdId, LocalDateTime now) {
        return mapper.freeLockedBedSlots(holdId, now);
    }

    @Override
    public List<Long> findExpiredLockedHoldIds(LocalDateTime now, int limit) {
        List<Long> ids = mapper.findExpiredLockedHoldIds(now, limit);
        return ids == null ? List.of() : ids;
    }

    @Override
    public int countLockedExpiredBefore(LocalDateTime cutoff) {
        return mapper.countLockedExpiredBefore(cutoff);
    }

    @Override
    public int confirmPaidTherapistSlots(long orderId, long holdId, int serviceEndSlotNo, LocalDateTime now) {
        return mapper.confirmPaidTherapistSlots(orderId, holdId, serviceEndSlotNo, now);
    }

    @Override
    public int confirmPaidBedSlots(long orderId, long holdId, int serviceEndSlotNo, LocalDateTime now) {
        return mapper.confirmPaidBedSlots(orderId, holdId, serviceEndSlotNo, now);
    }

    @Override
    public int markReleaseLockJobDone(long holdId, LocalDateTime now) {
        return mapper.markReleaseLockJobDone("hold:" + holdId, now);
    }

    @Override
    public int casOrderStatus(long orderId, String expectedStatus, String toStatus, LocalDateTime now) {
        return mapper.casOrderStatus(orderId, expectedStatus, toStatus, now);
    }

    @Override
    public int deleteOccupancyFromSlot(long orderId, int fromSlotNo) {
        return mapper.deleteOccupancyFromSlot(orderId, fromSlotNo);
    }

    @Override
    public int freeOrderTherapistSlotsFrom(long orderId, int fromSlotNo, LocalDateTime now) {
        return mapper.freeOrderTherapistSlotsFrom(orderId, fromSlotNo, now);
    }

    @Override
    public int freeOrderBedSlotsFrom(long orderId, int fromSlotNo, LocalDateTime now) {
        return mapper.freeOrderBedSlotsFrom(orderId, fromSlotNo, now);
    }

    @Override
    public int clearAddOnHold(long orderId, LocalDateTime now) {
        return mapper.clearAddOnHold(orderId, now);
    }

    @Override
    public int deleteOccupancyForHoldFromSlot(long holdId, int fromSlotNo) {
        return mapper.deleteOccupancyForHoldFromSlot(holdId, fromSlotNo);
    }

    @Override
    public int freeHoldTherapistSlotsFrom(long holdId, int fromSlotNo, LocalDateTime now) {
        return mapper.freeHoldTherapistSlotsFrom(holdId, fromSlotNo, now);
    }

    @Override
    public int freeHoldBedSlotsFrom(long holdId, int fromSlotNo, LocalDateTime now) {
        return mapper.freeHoldBedSlotsFrom(holdId, fromSlotNo, now);
    }

    @Override
    public int restoreBufferSlots(
            long orderId, int fromSlotNo, int toExclusive, long mainHoldId, LocalDateTime now) {
        return mapper.restoreTherapistBufferSlots(orderId, fromSlotNo, toExclusive, mainHoldId, now)
                + mapper.restoreBedBufferSlots(orderId, fromSlotNo, toExclusive, mainHoldId, now);
    }

    @Override
    public int reassignOccupancyHold(long orderId, int fromSlotNo, int toExclusive, long mainHoldId) {
        return mapper.reassignOccupancyHold(orderId, fromSlotNo, toExclusive, mainHoldId);
    }

    @Override
    public int deleteUnpaidAddOnItems(long orderId) {
        return mapper.deleteUnpaidAddOnItems(orderId);
    }

    @Override
    public SlotHoldMeta findHoldSlotMeta(long holdId) {
        SlotHoldMeta meta = mapper.findTherapistHoldMeta(holdId);
        return meta != null ? meta : mapper.findBedHoldMeta(holdId);
    }

    @Override
    public List<Long> claimDueJobs(String instanceId, LocalDateTime now, int leaseSeconds, int limit) {
        List<Long> ids = mapper.findClaimableJobIds(now, limit);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        mapper.markJobsRunning(csvLongs(ids), instanceId, now, now.plusSeconds(leaseSeconds));
        return List.copyOf(ids);
    }

    @Override
    public DelayedJobRow findJob(long id) {
        return mapper.findJob(id);
    }

    @Override
    public int completeJob(long id, String status, String lastError, LocalDateTime now) {
        return mapper.completeJob(id, status, lastError, now);
    }

    /** slot_no values are ints; safe to interpolate into IN (...). */
    static String csv(List<Integer> slotNos) {
        if (slotNos == null || slotNos.isEmpty()) {
            throw new IllegalArgumentException("slotNos required");
        }
        return slotNos.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
    }

    static String csvLongs(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("ids required");
        }
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
}
