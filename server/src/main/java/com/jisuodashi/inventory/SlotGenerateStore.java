package com.jisuodashi.inventory;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/** Persistence port for calendar generation. Tests use an in-memory fake. */
public interface SlotGenerateStore {

    List<TherapistRef> listActiveTherapists();

    List<StoreRef> listActiveStores();

    List<BedRef> listActiveBeds();

    List<ScheduleTemplateView> listActiveTemplates(long therapistId, int weekday, LocalDate date);

    List<ScheduleExceptionView> listApprovedExceptions(long therapistId, LocalDate date);

    Map<Integer, ExistingTherapistSlot> listTherapistSlots(long therapistId, LocalDate date);

    boolean existsTherapistSlotInRange(LocalDate from, LocalDate to);

    /** @return true if the row was inserted */
    boolean insertTherapistSlotIgnore(TherapistSlotInsert row);

    /** @return true if the row was inserted */
    boolean insertBedSlotIgnore(BedSlotInsert row);

    /** Idempotent on {@code biz_key}. */
    void insertHumanTaskIgnore(HumanTaskInsert row);

    record TherapistRef(long id, long homeStoreId) {
    }

    record StoreRef(long id, LocalTime businessStart, LocalTime businessEnd) {
    }

    record BedRef(long id, long storeId) {
    }

    record ScheduleTemplateView(
            long id,
            long therapistId,
            long storeId,
            int weekday,
            LocalTime startTime,
            LocalTime endTime,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            int status
    ) {
    }

    record ScheduleExceptionView(
            long id,
            long therapistId,
            Long storeId,
            LocalDate exceptDate,
            String type,
            LocalTime startTime,
            LocalTime endTime,
            String status
    ) {
    }

    record ExistingTherapistSlot(long storeId, String status) {
    }

    record TherapistSlotInsert(
            long id,
            long therapistId,
            long storeId,
            LocalDate slotDate,
            int slotNo,
            String status
    ) {
    }

    record BedSlotInsert(
            long id,
            long bedId,
            long storeId,
            LocalDate slotDate,
            int slotNo,
            String status
    ) {
    }

    record HumanTaskInsert(
            long id,
            String taskType,
            String bizKey,
            String title,
            String detail,
            Long storeId
    ) {
    }
}
