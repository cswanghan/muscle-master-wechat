package com.jisuodashi.payment;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    public record PayRequest(
            @NotBlank(message = "requestId 不能为空") String requestId
    ) {
    }

    public record PayResponse(
            String orderId,
            String paymentNo,
            String status,
            long amountFen,
            boolean reused,
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
}
