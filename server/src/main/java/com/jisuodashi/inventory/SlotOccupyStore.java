package com.jisuodashi.inventory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Persistence port for lockNew. Tests use an in-memory CAS fake. */
public interface SlotOccupyStore {

    void beginWork();

    void commitWork();

    void rollbackWork();

    ProjectRef loadProject(long projectId);

    TherapistRef loadTherapist(long therapistId);

    /** Active beds of the store, ORDER BY sort_no, id. */
    List<BedRef> listBeds(long storeId);

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

    /** SELECT … FOR UPDATE WHERE status='FREE' ORDER BY slot_no. Does not pin busy rows. */
    List<SlotRow> lockFreeTherapistSlots(long therapistId, LocalDate date, List<Integer> slotNos);

    List<SlotRow> lockFreeBedSlots(long bedId, LocalDate date, List<Integer> slotNos);

    boolean occupancyExists(String resourceType, long resourceId, LocalDate date, List<Integer> slotNos);

    /** CAS: only FREE rows. dest = last BUFFER, others LOCKED. */
    int casLockTherapistSlots(long therapistId, LocalDate date, List<Integer> slotNos, int bufferFrom,
                              long orderId, long holdId, LocalDateTime expireAt, LocalDateTime now);

    int casLockBedSlots(long bedId, LocalDate date, List<Integer> slotNos, int bufferFrom,
                        long orderId, long holdId, LocalDateTime expireAt, LocalDateTime now);

    void insertOccupancy(OccupancyInsert row);

    void revertBedHold(long bedId, long holdId, LocalDateTime now);

    void insertOrder(BookingOrderInsert row);

    void insertOrderItem(OrderItemInsert row);

    void insertDelayedJob(DelayedJobInsert row);

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
            int bufferSlots
    ) {
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
