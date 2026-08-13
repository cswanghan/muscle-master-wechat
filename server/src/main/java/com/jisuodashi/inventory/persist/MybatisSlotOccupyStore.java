package com.jisuodashi.inventory.persist;

import com.jisuodashi.inventory.DuplicateOccupancyException;
import com.jisuodashi.inventory.SlotOccupyStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Repository
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
    public List<SlotRow> lockFreeTherapistSlots(long therapistId, LocalDate date, List<Integer> slotNos) {
        return mapper.lockFreeTherapistSlots(therapistId, date, csv(slotNos));
    }

    @Override
    public List<SlotRow> lockFreeBedSlots(long bedId, LocalDate date, List<Integer> slotNos) {
        return mapper.lockFreeBedSlots(bedId, date, csv(slotNos));
    }

    @Override
    public boolean occupancyExists(String resourceType, long resourceId, LocalDate date, List<Integer> slotNos) {
        return mapper.occupancyExists(resourceType, resourceId, date, csv(slotNos));
    }

    @Override
    public int casLockTherapistSlots(
            long therapistId, LocalDate date, List<Integer> slotNos, int bufferFrom,
            long orderId, long holdId, LocalDateTime expireAt, LocalDateTime now) {
        return mapper.casLockTherapistSlots(
                therapistId, date, csv(slotNos), bufferFrom, orderId, holdId, expireAt, now);
    }

    @Override
    public int casLockBedSlots(
            long bedId, LocalDate date, List<Integer> slotNos, int bufferFrom,
            long orderId, long holdId, LocalDateTime expireAt, LocalDateTime now) {
        return mapper.casLockBedSlots(bedId, date, csv(slotNos), bufferFrom, orderId, holdId, expireAt, now);
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

    /** slot_no values are ints; safe to interpolate into IN (...). */
    static String csv(List<Integer> slotNos) {
        if (slotNos == null || slotNos.isEmpty()) {
            throw new IllegalArgumentException("slotNos required");
        }
        return slotNos.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
    }
}
