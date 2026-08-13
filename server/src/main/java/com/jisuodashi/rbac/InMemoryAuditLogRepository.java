package com.jisuodashi.rbac;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
@Profile("dev")
public class InMemoryAuditLogRepository implements AuditLogRepository {

    private final List<AuditLogEntry> rows = new CopyOnWriteArrayList<>();

    @Override
    public void insert(AuditLogEntry entry) {
        rows.add(entry);
    }

    @Override
    public List<AuditLogEntry> listRecent(int limit) {
        int from = Math.max(0, rows.size() - limit);
        return new ArrayList<>(rows.subList(from, rows.size()));
    }

    @Override
    public void clear() {
        rows.clear();
    }
}
