package com.jisuodashi.admin;

import java.util.List;

public final class AdminDtos {

    private AdminDtos() {
    }

    public record TherapistItem(
            String therapistId,
            String employeeNo,
            String name,
            String homeStoreId,
            String level,
            String intro,
            int ratingX100,
            int status,
            List<String> projectIds,
            List<String> symptomIds
    ) {
    }

    public record TherapistUpsertRequest(
            String employeeNo,
            String name,
            String homeStoreId,
            String level,
            String intro,
            Integer status,
            List<String> projectIds,
            List<String> symptomIds
    ) {
    }

    public record ProjectItem(
            String projectId,
            String code,
            String name,
            int durationMinutes,
            int bufferMinutes,
            long priceFen,
            String description,
            int status
    ) {
    }

    public record ProjectUpsertRequest(
            String code,
            String name,
            Integer durationMinutes,
            Integer bufferMinutes,
            Long priceFen,
            String description,
            Integer status
    ) {
    }

    public record TemplateItem(
            String templateId,
            String therapistId,
            String storeId,
            int weekday,
            String startTime,
            String endTime,
            String effectiveFrom,
            String effectiveTo,
            int status
    ) {
    }

    public record TemplateUpsertRequest(
            String therapistId,
            String storeId,
            Integer weekday,
            String startTime,
            String endTime,
            String effectiveFrom,
            String effectiveTo,
            Integer status
    ) {
    }

    public record Page<T>(List<T> items, String nextCursor) {
        public static <T> Page<T> of(List<T> items) {
            return new Page<>(items, null);
        }
    }

    public record OrderItem(
            String orderId,
            String orderNo,
            String storeId,
            String therapistId,
            String status,
            String serviceDate,
            String createdAt,
            long payableFen,
            boolean highlight
    ) {
    }

    public record OrderListResponse(List<OrderItem> items, String nextCursor, String view) {
    }
}
