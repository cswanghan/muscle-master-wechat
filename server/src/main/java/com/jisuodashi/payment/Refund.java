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
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String WAIT_APPROVAL = "WAIT_APPROVAL";

    public boolean pending() {
        return PENDING.equals(status);
    }

    public boolean success() {
        return SUCCESS.equals(status);
    }

    public boolean waitApproval() {
        return WAIT_APPROVAL.equals(status);
    }

    public boolean failed() {
        return FAILED.equals(status);
    }

    public boolean open() {
        return pending() || waitApproval();
    }

    public Refund withStatus(String next, LocalDateTime now) {
        return new Refund(
                id, refundNo, paymentId, orderId, amountFen, reason, next,
                wxRefundId, operatorId, createdAt, now);
    }

    public Refund succeeded(String wxId, LocalDateTime now) {
        return new Refund(
                id, refundNo, paymentId, orderId, amountFen, reason, SUCCESS,
                wxId, operatorId, createdAt, now);
    }

    public Refund failed(LocalDateTime now) {
        return withStatus(FAILED, now);
    }
}
