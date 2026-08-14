package com.jisuodashi.inventory;

import java.util.List;

/** Wire shapes for {@code /a/schedule-exceptions}. Ids and times travel as strings (admin convention). */
public final class ScheduleExceptionDtos {

    private ScheduleExceptionDtos() {
    }

    public record ApplyRequest(
            String requestId,
            String therapistId,
            String storeId,
            String date,
            String type,
            String startTime,
            String endTime,
            String reason
    ) {
    }

    /** {@code restSlots} is only filled by approve: how many FREE slots became REST. */
    public record ExceptionView(
            String id,
            String therapistId,
            String storeId,
            String date,
            String type,
            String startTime,
            String endTime,
            String reason,
            String status,
            String taskBizKey,
            Integer restSlots
    ) {
    }

    public record ListResponse(List<ExceptionView> items) {
    }

    public record DecisionRequest(String requestId, String note) {
    }
}
