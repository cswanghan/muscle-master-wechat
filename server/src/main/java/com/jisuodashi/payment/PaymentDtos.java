package com.jisuodashi.payment;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    public record PayRequest(
            @NotBlank(message = "requestId 不能为空") String requestId
    ) {
    }

    /**
     * {@code mock} tells the caller the prepay params came from
     * {@link MockWeChatPayClient} and cannot be charged, so a client may drive the notify
     * callback itself instead of calling {@code wx.requestPayment}. A real channel never sets
     * it, which is what keeps that shortcut out of production.
     */
    public record PayResponse(
            String orderId,
            String paymentNo,
            String status,
            long amountFen,
            boolean reused,
            boolean mock,
            Map<String, String> payParams
    ) {
    }

    public record NativePayResponse(
            String orderId,
            String paymentNo,
            String status,
            long amountFen,
            boolean reused,
            String codeUrl
    ) {
    }

    public record PaymentView(
            String paymentNo,
            String status,
            long amountFen,
            String orderId
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WechatNotifyAck(String code, String message) {
        public static WechatNotifyAck success() {
            return new WechatNotifyAck("SUCCESS", null);
        }

        public static WechatNotifyAck fail(String message) {
            return new WechatNotifyAck("FAIL", message);
        }
    }

    public record RefundOutcome(
            String orderId,
            String orderStatus,
            String workflowStatus,
            List<Refund> refunds,
            boolean replay
    ) {
    }

    public record HumanTaskItem(
            String id,
            String taskType,
            String title,
            String status,
            String orderId,
            String bizKey
    ) {
    }
}
