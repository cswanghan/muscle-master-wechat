package com.jisuodashi.common;

public record ApiResponse<T>(int code, String message, String requestId, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCodes.OK, "ok", RequestIds.current(), data);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, RequestIds.current(), null);
    }
}
