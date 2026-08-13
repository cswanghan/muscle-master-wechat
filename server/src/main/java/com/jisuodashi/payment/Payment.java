package com.jisuodashi.payment;

import java.time.LocalDateTime;

/** One row per WeChat prepay (1:1). Callback is idempotent on {@code paymentNo}. */
public record Payment(
        long id,
        String paymentNo,
        long orderId,
        String channel,
        long amountFen,
        String status,
        String wxPrepayId,
        String wxTransactionId,
        LocalDateTime paidAt,
        String notifyRaw,
        LocalDateTime expireAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static final String CHANNEL_WECHAT = "WECHAT";
    public static final String CHANNEL_CASH = "CASH";
    public static final String PENDING = "PENDING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String CLOSED = "CLOSED";

    public boolean pending() {
        return PENDING.equals(status);
    }

    public boolean success() {
        return SUCCESS.equals(status);
    }

    public boolean prepayExpired(LocalDateTime now) {
        return expireAt != null && !expireAt.isAfter(now);
    }

    public Payment withStatus(String next, LocalDateTime now) {
        return new Payment(
                id, paymentNo, orderId, channel, amountFen, next,
                wxPrepayId, wxTransactionId, paidAt, notifyRaw, expireAt, createdAt, now);
    }

    public Payment closed(LocalDateTime now) {
        return withStatus(CLOSED, now);
    }

    public Payment failed(String raw, LocalDateTime now) {
        return new Payment(
                id, paymentNo, orderId, channel, amountFen, FAILED,
                wxPrepayId, wxTransactionId, paidAt, raw, expireAt, createdAt, now);
    }

    public Payment paid(String txn, String raw, LocalDateTime now) {
        return new Payment(
                id, paymentNo, orderId, channel, amountFen, SUCCESS,
                wxPrepayId, txn, now, raw, expireAt, createdAt, now);
    }
}
