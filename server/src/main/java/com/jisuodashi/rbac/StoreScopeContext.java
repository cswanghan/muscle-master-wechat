package com.jisuodashi.rbac;

public final class StoreScopeContext {

    private static final ThreadLocal<StoreScope> HOLDER = new ThreadLocal<>();

    private StoreScopeContext() {
    }

    public static void set(StoreScope scope) {
        HOLDER.set(scope);
    }

    public static StoreScope get() {
        return HOLDER.get();
    }

    public static StoreScope require() {
        StoreScope scope = HOLDER.get();
        if (scope == null) {
            throw new IllegalStateException("StoreScope is not bound");
        }
        return scope;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
