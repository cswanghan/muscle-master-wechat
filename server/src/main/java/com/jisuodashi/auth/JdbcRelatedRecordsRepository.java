package com.jisuodashi.auth;

import com.jisuodashi.common.JdbcTimes;
import com.jisuodashi.common.SnowflakeIdGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Instant;

@Repository
@Profile("!dev")
public class JdbcRelatedRecordsRepository implements RelatedRecordsRepository {

    private final JdbcTemplate jdbc;
    private final SnowflakeIdGenerator ids;
    private final Clock clock;

    public JdbcRelatedRecordsRepository(JdbcTemplate jdbc, SnowflakeIdGenerator ids, Clock clock) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public void reassignBookings(long fromCustomerId, long toCustomerId) {
        jdbc.update("UPDATE booking_order SET customer_id=? WHERE customer_id=?", toCustomerId, fromCustomerId);
    }

    @Override
    public void reassignSessions(long fromCustomerId, long toCustomerId) {
        jdbc.update(
                "UPDATE auth_session SET subject_id=? WHERE subject_type='CUSTOMER' AND subject_id=?",
                toCustomerId,
                fromCustomerId);
    }

    @Override
    public void reassignServiceRecords(long fromCustomerId, long toCustomerId) {
        jdbc.update("UPDATE service_record SET customer_id=? WHERE customer_id=?", toCustomerId, fromCustomerId);
    }

    @Override
    public void insertCollisionTask(String phoneHash) {
        String bizKey = CollisionKeys.bizKey(phoneHash);
        try {
            jdbc.update(
                    """
                    INSERT INTO human_task
                      (id, workflow_instance_id, order_id, task_type, biz_key, title, detail, status,
                       assignee_role, store_id, created_at, resolved_at, resolved_by)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    ids.nextId(),
                    null,
                    null,
                    "CUSTOMER_COLLISION",
                    bizKey,
                    "客户身份冲突",
                    null,
                    "OPEN",
                    null,
                    null,
                    JdbcTimes.ts(Instant.now(clock)),
                    null,
                    null);
        } catch (DuplicateKeyException ignored) {
            // uk_ht_biz: collision already queued
        }
    }

    @Override
    public void insertMergeAudit(long fromId, long toId) {
        jdbc.update(
                """
                INSERT INTO audit_log
                  (id, actor_id, actor_type, action, resource_type, resource_id, store_id,
                   ip, user_agent, request_id, before_json, after_json, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                ids.nextId(),
                null,
                "SYSTEM",
                "CUSTOMER_MERGE",
                "CUSTOMER",
                toId,
                null,
                null,
                null,
                null,
                "{\"from\":" + fromId + "}",
                "{\"to\":" + toId + "}",
                JdbcTimes.ts(Instant.now(clock)));
    }
}
