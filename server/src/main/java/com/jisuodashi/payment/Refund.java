package com.jisuodashi.payment;

import java.time.LocalDateTime;

public record Refund(
        long id,
        String refundNo,
        long paymentId,
        long orderId,
        long amountFen,
        String reason,
        String status,
        String wxRefundId,
        Long operatorId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static final String PENDING = "PENDING";
}
