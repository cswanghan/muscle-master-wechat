package com.jisuodashi.staff;

import com.jisuodashi.common.JdbcTimes;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("!dev")
public class JdbcTreatmentNoteRepository implements TreatmentNoteRepository {

    private static final RowMapper<TreatmentNote> ROW = (rs, i) -> new TreatmentNote(
            rs.getLong("id"),
            rs.getLong("order_id"),
            rs.getLong("store_id"),
            rs.getLong("therapist_id"),
            rs.getLong("author_staff_id"),
            rs.getString("content"),
            JdbcTimes.instant(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;

    public JdbcTreatmentNoteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<TreatmentNote> findByOrderId(long orderId) {
        return jdbc.query(
                """
                SELECT n.id, n.order_id, n.author_staff_id, n.content, n.created_at,
                       COALESCE(sr.store_id, o.store_id) AS store_id,
                       COALESCE(sr.therapist_id, o.therapist_id) AS therapist_id
                  FROM treatment_note n
                  JOIN booking_order o ON o.id = n.order_id
                  LEFT JOIN service_record sr ON sr.id = n.service_record_id
                 WHERE n.order_id = ?
                 ORDER BY n.id
                """,
                ROW,
                orderId);
    }

    @Override
    public TreatmentNote insert(TreatmentNote note) {
        ServiceRecord record = findLatestServiceRecord(note.orderId())
                .orElseThrow(() -> new IllegalStateException("service_record missing for order " + note.orderId()));
        jdbc.update(
                """
                INSERT INTO treatment_note
                  (id, service_record_id, order_id, author_staff_id, content, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                note.id(),
                record.id(),
                note.orderId(),
                note.authorStaffId(),
                note.content(),
                JdbcTimes.ts(note.createdAt()));
        return note;
    }

    @Override
    public Optional<ServiceRecord> findLatestServiceRecord(long orderId) {
        List<ServiceRecord> rows = jdbc.query(
                """
                SELECT id, order_id, therapist_id, customer_id, store_id, started_at, ended_at, created_at
                  FROM service_record
                 WHERE order_id = ?
                 ORDER BY id DESC
                 LIMIT 1
                """,
                (rs, i) -> new ServiceRecord(
                        rs.getLong("id"),
                        rs.getLong("order_id"),
                        rs.getLong("therapist_id"),
                        rs.getLong("customer_id"),
                        rs.getLong("store_id"),
                        JdbcTimes.instant(rs.getTimestamp("started_at")),
                        JdbcTimes.instant(rs.getTimestamp("ended_at")),
                        JdbcTimes.instant(rs.getTimestamp("created_at"))),
                orderId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public ServiceRecord ensureServiceRecord(
            long id, long orderId, long therapistId, long customerId, long storeId, Instant now) {
        Optional<ServiceRecord> existing = findLatestServiceRecord(orderId);
        if (existing.isPresent()) {
            return existing.get();
        }
        jdbc.update(
                """
                INSERT INTO service_record
                  (id, order_id, therapist_id, customer_id, store_id, started_at, ended_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, NULL, ?)
                """,
                id, orderId, therapistId, customerId, storeId, JdbcTimes.ts(now), JdbcTimes.ts(now));
        return new ServiceRecord(id, orderId, therapistId, customerId, storeId, now, null, now);
    }

    @Override
    public void markLatestEnded(long orderId, Instant endedAt) {
        jdbc.update(
                """
                UPDATE service_record
                   SET ended_at = ?
                 WHERE id = (
                       SELECT id FROM (
                         SELECT id FROM service_record WHERE order_id = ? ORDER BY id DESC LIMIT 1
                       ) t)
                """,
                JdbcTimes.ts(endedAt),
                orderId);
    }

    @Override
    public ServiceRecord insertServiceRecord(
            long id, long orderId, long therapistId, long customerId, long storeId, Instant now) {
        jdbc.update(
                """
                INSERT INTO service_record
                  (id, order_id, therapist_id, customer_id, store_id, started_at, ended_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, NULL, ?)
                """,
                id, orderId, therapistId, customerId, storeId, JdbcTimes.ts(now), JdbcTimes.ts(now));
        return new ServiceRecord(id, orderId, therapistId, customerId, storeId, now, null, now);
    }

    @Override
    public List<ServiceRecord> listServiceRecords(long orderId) {
        return jdbc.query(
                """
                SELECT id, order_id, therapist_id, customer_id, store_id, started_at, ended_at, created_at
                  FROM service_record
                 WHERE order_id = ?
                 ORDER BY id
                """,
                (rs, i) -> new ServiceRecord(
                        rs.getLong("id"),
                        rs.getLong("order_id"),
                        rs.getLong("therapist_id"),
                        rs.getLong("customer_id"),
                        rs.getLong("store_id"),
                        JdbcTimes.instant(rs.getTimestamp("started_at")),
                        JdbcTimes.instant(rs.getTimestamp("ended_at")),
                        JdbcTimes.instant(rs.getTimestamp("created_at"))),
                orderId);
    }

    @Override
    public void insertSystemNote(long id, long orderId, long authorStaffId, String content, Instant now) {
        long recordId = findLatestServiceRecord(orderId).map(ServiceRecord::id).orElse(0L);
        jdbc.update(
                """
                INSERT INTO treatment_note
                  (id, service_record_id, order_id, author_staff_id, content, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                id, recordId, orderId, authorStaffId, content, JdbcTimes.ts(now));
    }
}
