package com.jisuodashi.rbac;

/** Request-scoped ids the audit aspect cannot always recover from the URL. */
public final class AuditHints {

    private static final ThreadLocal<Long> STORE = new ThreadLocal<>();
    private static final ThreadLocal<Long> RESOURCE = new ThreadLocal<>();

    private AuditHints() {
    }

    public static void setStoreId(Long storeId) {
        if (storeId != null) {
            STORE.set(storeId);
        }
    }

    public static void setResourceId(Long resourceId) {
        if (resourceId != null) {
            RESOURCE.set(resourceId);
        }
    }

    public static Long storeId() {
        return STORE.get();
    }

    public static Long resourceId() {
        return RESOURCE.get();
    }

    public static void clear() {
        STORE.remove();
        RESOURCE.remove();
    }
}
