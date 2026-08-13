package com.jisuodashi.frontdesk;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public final class FrontDeskDtos {

    private FrontDeskDtos() {
    }

    public static final String VERIFY_ORDER_NO = "ORDER_NO";
    public static final String VERIFY_PHONE = "PHONE";
    public static final String CASH = "CASH";
    public static final String WECHAT = "WECHAT";

    public record CheckInRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            String verify,
            String keyword
    ) {
    }

    public record CheckInResponse(
            String orderId,
            String status,
            String roomName,
            String bedName,
            String customerMask
    ) {
    }

    public record WalkInRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "手机号不能为空") String phone,
            String customerName,
            String storeId,
            @NotBlank(message = "therapistId 不能为空") String therapistId,
            @NotBlank(message = "projectId 不能为空") String projectId,
            @NotNull(message = "date 不能为空") LocalDate date,
            @NotNull(message = "startSlotNo 不能为空")
            @Min(value = 0, message = "startSlotNo 无效") Integer startSlotNo,
            Boolean alreadyInStore,
            @NotBlank(message = "payChannel 不能为空") String payChannel,
            String remark
    ) {
    }

    public record WalkInResponse(
            String orderId,
            String orderNo,
            String status,
            String customerId,
            String payChannel,
            String paymentNo,
            String codeUrl,
            long payableFen,
            boolean alreadyInStore,
            boolean replay,
            String roomName,
            String bedName,
            String customerMask
    ) {
    }

    public record OrderPreview(
            String orderId,
            String orderNo,
            String status,
            String roomName,
            String bedName,
            String customerMask,
            String therapistId,
            int startSlotNo,
            String serviceDate
    ) {
    }

    public record LookupResponse(List<OrderPreview> items) {
    }

    public record AddOnRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "projectId 不能为空") String projectId,
            @NotNull(message = "durationMinutes 不能为空")
            @Min(value = 15, message = "durationMinutes 必须是 15 的倍数且 ≥15") Integer durationMinutes,
            @NotBlank(message = "payChannel 不能为空") String payChannel
    ) {
    }

    public record AddOnResponse(
            String orderId,
            String status,
            String payChannel,
            String paymentNo,
            String codeUrl,
            long amountFen,
            int endSlotNo,
            boolean replay
    ) {
    }

    public record SwapTherapistRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "newTherapistId 不能为空") String newTherapistId,
            String reason
    ) {
    }

    public record SwapTherapistResponse(
            String orderId,
            String status,
            String oldTherapistId,
            String newTherapistId,
            int fromSlotNo,
            boolean replay
    ) {
    }
}
