package com.jisuodashi.rbac;

import com.jisuodashi.common.JdbcTimes;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@Profile("!dev")
public class JdbcAuditLogRepository implements AuditLogRepository {

    private static final RowMapper<AuditLogEntry> ROW = (rs, i) -> {
        AuditLogEntry e = new AuditLogEntry();
        e.setId(rs.getLong("id"));
        long actor = rs.getLong("actor_id");
        e.setActorId(rs.wasNull() ? null : actor);
        e.setActorType(rs.getString("actor_type"));
        e.setAction(rs.getString("action"));
        e.setResourceType(rs.getString("resource_type"));
        long resource = rs.getLong("resource_id");
        e.setResourceId(rs.wasNull() ? null : resource);
        long store = rs.getLong("store_id");
        e.setStoreId(rs.wasNull() ? null : store);
        e.setIp(rs.getString("ip"));
        e.setUserAgent(rs.getString("user_agent"));
        e.setRequestId(rs.getString("request_id"));
        e.setBeforeJson(rs.getString("before_json"));
        e.setAfterJson(rs.getString("after_json"));
        e.setCreatedAt(JdbcTimes.instant(rs.getTimestamp("created_at")));
        return e;
    };

    private final JdbcTemplate jdbc;

    public JdbcAuditLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(AuditLogEntry entry) {
        jdbc.update(
                """
                INSERT INTO audit_log
                  (id, actor_id, actor_type, action, resource_type, resource_id, store_id,
                   ip, user_agent, request_id, before_json, after_json, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                entry.getId(),
                entry.getActorId(),
                entry.getActorType(),
                entry.getAction(),
                entry.getResourceType(),
                entry.getResourceId(),
                entry.getStoreId(),
                entry.getIp(),
                entry.getUserAgent(),
                entry.getRequestId(),
                entry.getBeforeJson(),
                entry.getAfterJson(),
                JdbcTimes.ts(entry.getCreatedAt()));
    }

    @Override
    public List<AuditLogEntry> listRecent(int limit) {
        return jdbc.query(
                "SELECT * FROM audit_log ORDER BY created_at DESC LIMIT ?",
                ROW,
                limit);
    }
}
