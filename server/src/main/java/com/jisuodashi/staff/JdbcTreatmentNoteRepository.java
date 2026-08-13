package com.jisuodashi.staff;

import com.jisuodashi.common.JdbcTimes;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

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
}
