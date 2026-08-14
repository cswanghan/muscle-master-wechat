package com.jisuodashi.inventory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Persistence port for {@code schedule_exception} (§2.3). The generation side reads APPROVED rows
 * through {@link SlotGenerateStore}; this port is the write side (apply / approve / reject).
 */
public interface ScheduleExceptionStore {

    String TYPE_LEAVE = "LEAVE";
    String TYPE_ADJUST = "ADJUST";
    String TYPE_SUPPORT = "SUPPORT";

    String STATUS_PENDING = "PENDING";
    String STATUS_APPROVED = "APPROVED";
    String STATUS_REJECTED = "REJECTED";

    void beginWork();

    void commitWork();

    void rollbackWork();

    void insert(ScheduleExceptionRow row);

    ScheduleExceptionRow findById(long id);

    ScheduleExceptionRow lockById(long id);

    /** @return rows updated; 0 means someone else moved the row first */
    int casStatus(long id, String expectedStatus, String nextStatus, LocalDateTime now);

    /** {@code storeIds} empty = no store filter (scope-all callers). */
    List<ScheduleExceptionRow> list(List<Long> storeIds, LocalDate from, LocalDate to, String status);

    record ScheduleExceptionRow(
            long id,
            long therapistId,
            Long storeId,
            LocalDate exceptDate,
            String type,
            LocalTime startTime,
            LocalTime endTime,
            String reason,
            String status,
            Long createdBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public ScheduleExceptionRow withStatus(String next, LocalDateTime now) {
            return new ScheduleExceptionRow(id, therapistId, storeId, exceptDate, type, startTime,
                    endTime, reason, next, createdBy, createdAt, now);
        }
    }
}
