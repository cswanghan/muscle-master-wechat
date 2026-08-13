package com.jisuodashi.order;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class BookingDtos {

    private BookingDtos() {
    }

    public record CreateBookingRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "storeId 不能为空") String storeId,
            @NotBlank(message = "therapistId 不能为空") String therapistId,
            @NotBlank(message = "projectId 不能为空") String projectId,
            @NotNull(message = "date 不能为空") LocalDate date,
            @NotNull(message = "startSlotNo 不能为空")
            @Min(value = 0, message = "startSlotNo 无效") Integer startSlotNo
    ) {
    }

    public record CreateBookingResponse(
            String orderId,
            String orderNo,
            String status,
            String lockExpireAt,
            long payableFen,
            Map<String, String> payParams
    ) {
    }

    public record CancelBookingRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            String reason
    ) {
    }

    public record CancelBookingResponse(
            String orderId,
            String status,
            String requestId
    ) {
    }

    public record PayRequest(
            @NotBlank(message = "requestId 不能为空") String requestId
    ) {
    }

    public record BookingListItem(
            String orderId,
            String orderNo,
            String status,
            long payableFen,
            String storeId,
            String therapistId,
            String date,
            int startSlotNo,
            String start,
            String lockExpireAt
    ) {
    }

    public record Page<T>(List<T> items, String nextCursor) {
        public static <T> Page<T> of(List<T> items) {
            return new Page<>(items, null);
        }
    }
}
