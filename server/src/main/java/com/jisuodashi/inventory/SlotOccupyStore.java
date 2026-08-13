package com.jisuodashi.inventory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Persistence port for lockNew / Release* / confirmPaid. Tests use an in-memory CAS fake. */
public interface SlotOccupyStore extends DelayedJobStore {

    void beginWork();

    void commitWork();

    void rollbackWork();

    ProjectRef loadProject(long projectId);

    TherapistRef loadTherapist(long therapistId);

    /** Active beds of the store, ORDER BY sort_no, id. */
    List<BedRef> listBeds(long storeId);

    Long loadStoreProjectPrice(long storeId, long projectId);

    Long loadSlotPriceOverride(long therapistId, LocalDate date, int startSlotNo);

    boolean insertIdempotency(IdemInsert row);

    IdemRow lockIdempotency(String scope, String requestId);

    int takeoverIdempotency(String scope, String requestId, int expectedVersion,
                            LocalDateTime expireAt, LocalDateTime now, String lockedBy);

    BookingOrderRef findOrderByRequestId(String requestId);

    /**
     * Must include {@code status='PROCESSING' AND version=?}. Never overwrites DONE.
     *
     * @return affected rows
     */
    int finishIdempotent(String scope, String requestId, int version, String responseBody, LocalDateTime now);

    /**
     * Preselect FREE ids (no lock), then {@code SELECT … FOR UPDATE} those ids
     * {@code AND status='FREE'} so busy rows are never in the lock set.
     */
    List<SlotRow> lockFreeTherapistSlots(long therapistId, LocalDate date, List<Integer> slotNos);

    List<SlotRow> lockFreeBedSlots(long bedId, LocalDate date, List<Integer> slotNos);

    boolean occupancyExists(String resourceType, long resourceId, LocalDate date, List<Integer> slotNos);

    /** CAS: only FREE → LOCKED. */
    int casLockTherapistSlots(long therapistId, LocalDate date, List<Integer> slotNos,
                              long orderId, long holdId, LocalDateTime expireAt, LocalDateTime now);

    int casLockBedSlots(long bedId, LocalDate date, List<Integer> slotNos,
                        long orderId, long holdId, LocalDateTime expireAt, LocalDateTime now);

    void insertOccupancy(OccupancyInsert row);

    /** Delete this hold's bed occupancy then FREE those slot rows. */
    void revertBedHold(long bedId, long holdId, LocalDateTime now);

    void insertOrder(BookingOrderInsert row);

    void insertOrderItem(OrderItemInsert row);

    void insertDelayedJob(DelayedJobInsert row);

    BookingOrderRef findOrderByHoldId(long holdId);

    BookingOrderRef findOrderByAddOnHoldId(long holdId);

    /** {@code SELECT … FOR UPDATE} on {@code booking_order.hold_id}. */
    BookingOrderRef lockOrderByHoldId(long holdId);

    BookingOrderRef lockOrderById(long orderId);

    default BookingOrderRef findOrderById(long orderId) {
        return lockOrderById(orderId);
    }

    /** Occupancy whose slot row is still LOCKED for this hold. */
    int deleteOccupancyForLockedHold(long holdId);

    int deleteOccupancyByHold(long holdId);

    int freeLockedTherapistSlots(long holdId, LocalDateTime now);

    int freeLockedBedSlots(long holdId, LocalDateTime now);

    List<Long> findExpiredLockedHoldIds(LocalDateTime now, int limit);

    /** LOCKED rows with {@code lock_expire_at < cutoff} on both slot tables. */
    int countLockedExpiredBefore(LocalDateTime cutoff);

    int confirmPaidTherapistSlots(long orderId, long holdId, int serviceEndSlotNo, LocalDateTime now);

    int confirmPaidBedSlots(long orderId, long holdId, int serviceEndSlotNo, LocalDateTime now);

    int markReleaseLockJobDone(long holdId, LocalDateTime now);

    /** CAS: only when current status matches. Used solely by {@code OrderStateMachine.fire}. */
    int casOrderStatus(long orderId, String expectedStatus, String toStatus, LocalDateTime now);

    int deleteOccupancyFromSlot(long orderId, int fromSlotNo);

    int freeOrderTherapistSlotsFrom(long orderId, int fromSlotNo, LocalDateTime now);

    int freeOrderBedSlotsFrom(long orderId, int fromSlotNo, LocalDateTime now);

    int clearAddOnHold(long orderId, LocalDateTime now);

    int deleteOccupancyForHoldFromSlot(long holdId, int fromSlotNo);

    int freeHoldTherapistSlotsFrom(long holdId, int fromSlotNo, LocalDateTime now);

    int freeHoldBedSlotsFrom(long holdId, int fromSlotNo, LocalDateTime now);

    int restoreBufferSlots(long orderId, int fromSlotNo, int toExclusive, long mainHoldId, LocalDateTime now);

    int reassignOccupancyHold(long orderId, int fromSlotNo, int toExclusive, long mainHoldId);

    int deleteUnpaidAddOnItems(long orderId);

    SlotHoldMeta findHoldSlotMeta(long holdId);

    record ProjectRef(long id, String name, int durationMinutes, int bufferMinutes, long priceFen) {
    }

    record TherapistRef(long id, long homeStoreId) {
    }

    record BedRef(long id, long storeId, long roomId, int sortNo) {
    }

    record SlotRow(int slotNo, String status) {
    }

    record IdemInsert(
            long id,
            String scope,
            String requestId,
            String status,
            int version,
            String lockedBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime expireAt
    ) {
    }

    record IdemRow(String status, int version, LocalDateTime expireAt, String responseBody) {
    }

    record OccupancyInsert(
            long id,
            String resourceType,
            long resourceId,
            LocalDate slotDate,
            int slotNo,
            long orderId,
            long holdId,
            LocalDateTime createdAt
    ) {
    }

    record BookingOrderRef(
            long id,
            String orderNo,
            long holdId,
            long bedId,
            long roomId,
            String status,
            LocalDateTime lockExpireAt,
            long payableFen,
            int startSlotNo,
            int endSlotNo,
            int bufferSlots,
            Long addOnHoldId,
            long storeId,
            LocalDate serviceDate,
            long customerId,
            long therapistId
    ) {
    }

    record SlotHoldMeta(long storeId, LocalDate slotDate) {
    }

    record BookingOrderInsert(
            long id,
            String orderNo,
            String requestId,
            long holdId,
            long customerId,
            long storeId,
            long therapistId,
            long therapistHomeStoreId,
            long bedId,
            long roomId,
            String status,
            String source,
            LocalDate serviceDate,
            int startSlotNo,
            int endSlotNo,
            int bufferSlots,
            long originPriceFen,
            long payableFen,
            LocalDateTime lockExpireAt,
            LocalDateTime createdAt
    ) {
    }

    record OrderItemInsert(
            long id,
            long orderId,
            String itemType,
            long projectId,
            String projectName,
            int durationMinutes,
            int bufferMinutes,
            int quantity,
            long unitPriceFen,
            long amountFen,
            int startSlotNo,
            int endSlotNo,
            LocalDateTime createdAt
    ) {
    }

    record DelayedJobInsert(
            long id,
            String jobType,
            String bizKey,
            String payload,
            LocalDateTime runAt,
            String status,
            LocalDateTime createdAt
    ) {
    }
}
