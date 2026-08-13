package com.jisuodashi.order;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;

/** Closed order statuses (D8 / §3). */
public enum OrderStatus {
    PENDING_PAY,
    BOOKED,
    CHECKED_IN,
    IN_SERVICE,
    ABNORMAL,
    COMPLETED,
    REVIEWED,
    CANCELLED,
    NO_SHOW,
    CLOSED;

    public static OrderStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "非法状态转移");
        }
        try {
            return OrderStatus.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "非法状态转移");
        }
    }
}
