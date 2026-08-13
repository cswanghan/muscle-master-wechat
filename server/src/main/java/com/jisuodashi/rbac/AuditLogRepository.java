package com.jisuodashi.rbac;

import java.util.List;

public interface AuditLogRepository {

    void insert(AuditLogEntry entry);

    List<AuditLogEntry> listRecent(int limit);

    default void clear() {
    }
}
