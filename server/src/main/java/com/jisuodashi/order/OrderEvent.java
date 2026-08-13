package com.jisuodashi.order;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;

/** Closed transfer-table events (D8 / §3.2). */
public enum OrderEvent {
    PAY_SUCCESS,
    PAY_TIMEOUT,
    USER_CANCEL,
    CHECK_IN,
    CANCEL,
    REFUND,
    RESCHEDULE,
    MARK_NO_SHOW,
    START_SERVICE,
    SWAP_THERAPIST,
    COMPLETE_SERVICE,
    ADD_ON,
    ADD_ON_PAY_TIMEOUT,
    ABORT,
    RESOLVE_COMPLETE,
    RESOLVE_CANCEL,
    REVIEW;

    /**
     * Accepts design names plus the PR brief aliases START / COMPLETE / NO_SHOW.
     */
    public static OrderEvent parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "非法状态转移");
        }
        String key = raw.trim();
        return switch (key) {
            case "START" -> START_SERVICE;
            case "COMPLETE" -> COMPLETE_SERVICE;
            case "NO_SHOW" -> MARK_NO_SHOW;
            default -> {
                try {
                    yield OrderEvent.valueOf(key);
                } catch (IllegalArgumentException ex) {
                    throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "非法状态转移");
                }
            }
        };
    }
}
