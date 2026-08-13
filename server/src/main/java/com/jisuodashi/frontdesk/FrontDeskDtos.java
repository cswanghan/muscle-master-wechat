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
            String customerMask,
            long remainingFen
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
            String serviceDate,
            long payableFen,
            long remainingFen
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

    public record RescheduleRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "date 不能为空") LocalDate date,
            @NotNull(message = "startSlotNo 不能为空")
            @Min(value = 0, message = "startSlotNo 无效") Integer startSlotNo,
            @NotBlank(message = "therapistId 不能为空") String therapistId
    ) {
    }

    public record RescheduleResponse(
            String orderId,
            String orderNo,
            String status,
            String therapistId,
            String bedId,
            String roomId,
            String serviceDate,
            int startSlotNo,
            int endSlotNo,
            String roomName,
            String bedName,
            String customerMask,
            boolean replay
    ) {
    }

    public record RefundRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "amountFen 不能为空")
            @Min(value = 1, message = "amountFen 无效") Long amountFen,
            String reason
    ) {
    }

    public record RefundView(
            String refundNo,
            String paymentId,
            long amountFen,
            String status,
            String wxRefundId
    ) {
    }

    public record RefundResponse(
            String orderId,
            String status,
            String workflowStatus,
            List<RefundView> refunds,
            boolean replay
    ) {
    }

    public record ApproveRequest(String requestId) {
    }

    public record HumanTaskView(
            String id,
            String taskType,
            String title,
            String status,
            String orderId,
            String bizKey
    ) {
    }

    public record HumanTaskListResponse(List<HumanTaskView> items) {
    }

    public record HourUtilization(int hour, Integer rateX10000) {
    }

    public record UtilizationResponse(
            String storeId,
            String date,
            Integer rateX10000,
            List<HourUtilization> byHour
    ) {
    }
}
