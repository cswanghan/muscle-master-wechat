package com.jisuodashi.common;

import org.slf4j.MDC;

public final class RequestIds {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private RequestIds() {
    }

    public static void set(String requestId) {
        HOLDER.set(requestId);
        MDC.put(MDC_KEY, requestId);
    }

    public static String current() {
        String id = HOLDER.get();
        return id == null ? "" : id;
    }

    public static void clear() {
        HOLDER.remove();
        MDC.remove(MDC_KEY);
    }
}
