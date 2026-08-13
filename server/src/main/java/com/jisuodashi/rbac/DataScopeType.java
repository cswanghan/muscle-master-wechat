package com.jisuodashi.rbac;

/** P0 data_scope.scope_type. REGION is not a type. */
public enum DataScopeType {
    ALL,
    STORE,
    SELF;

    public static DataScopeType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return SELF;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return SELF;
        }
    }
}
