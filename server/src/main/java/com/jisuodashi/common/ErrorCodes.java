package com.jisuodashi.common;

import org.springframework.http.HttpStatus;

/** Design §API error table. HTTP status is paired here so handlers stay consistent. */
public final class ErrorCodes {

    public static final int OK = 0;
    public static final int BAD_REQUEST = 40001;
    public static final int UNAUTHORIZED = 40101;
    public static final int TOKEN_EXPIRED = 40102;
    public static final int FORBIDDEN = 40301;
    public static final int DATA_SCOPE = 40302;
    public static final int NOT_FOUND = 40401;
    public static final int PREPAY_FAILED = 40201;
    public static final int SLOT_UNAVAILABLE = 40901;
    public static final int NO_FREE_BED = 40902;
    public static final int LOCK_CONFLICT = 40903;
    public static final int ILLEGAL_TRANSITION = 40904;
    public static final int PAY_EXPIRED = 40905;
    public static final int LEAVE_CONFLICT = 40906;
    public static final int ADD_ON_CONFLICT = 40907;
    public static final int CUSTOMER_COLLISION = 40908;
    public static final int INTERNAL = 50001;
    public static final int CHANNEL_ERROR = 50002;

    private ErrorCodes() {
    }

    public static HttpStatus httpStatus(int code) {
        return switch (code) {
            case OK -> HttpStatus.OK;
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case UNAUTHORIZED, TOKEN_EXPIRED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN, DATA_SCOPE -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case PREPAY_FAILED -> HttpStatus.PAYMENT_REQUIRED;
            case CHANNEL_ERROR -> HttpStatus.BAD_GATEWAY;
            case CUSTOMER_COLLISION, SLOT_UNAVAILABLE, NO_FREE_BED, LOCK_CONFLICT, ILLEGAL_TRANSITION,
                    PAY_EXPIRED, LEAVE_CONFLICT, ADD_ON_CONFLICT
                    -> HttpStatus.CONFLICT;
            default -> {
                if (code >= 40900 && code < 41000) {
                    yield HttpStatus.CONFLICT;
                }
                yield code >= 40000 && code < 50000
                        ? HttpStatus.BAD_REQUEST
                        : HttpStatus.INTERNAL_SERVER_ERROR;
            }
        };
    }
}
