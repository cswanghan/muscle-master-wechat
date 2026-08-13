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
    public static final int CUSTOMER_COLLISION = 40908;
    public static final int INTERNAL = 50001;

    private ErrorCodes() {
    }

    public static HttpStatus httpStatus(int code) {
        return switch (code) {
            case OK -> HttpStatus.OK;
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case UNAUTHORIZED, TOKEN_EXPIRED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN, DATA_SCOPE -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CUSTOMER_COLLISION -> HttpStatus.CONFLICT;
            default -> code >= 40000 && code < 50000
                    ? HttpStatus.BAD_REQUEST
                    : HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
