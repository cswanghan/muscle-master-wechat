package com.jisuodashi.rbac;

/** Shared store SQL so MyBatis and the Jdbc wrapper rewrite the same statements. */
public final class ScopedStoreQueries {

    public static final String LIST =
            "SELECT id, code, name, status FROM store WHERE deleted_at IS NULL ORDER BY id";
    public static final String FIND =
            "SELECT id, code, name, status FROM store WHERE id=? AND deleted_at IS NULL";
    public static final String UPDATE_STATUS =
            "UPDATE store SET status=?, updated_at=? WHERE id=? AND deleted_at IS NULL";
    public static final String INSERT =
            "INSERT INTO store (id, code, name, business_start, business_end, timezone, status, created_at, updated_at) "
                    + "VALUES (?,?,?,'10:00:00','22:00:00','Asia/Shanghai',?,?,?)";
    public static final String UPDATE =
            "UPDATE store SET name=?, status=?, updated_at=? WHERE id=? AND deleted_at IS NULL";
    public static final String SOFT_DELETE =
            "UPDATE store SET deleted_at=?, updated_at=? WHERE id=? AND deleted_at IS NULL";

    private ScopedStoreQueries() {
    }
}
