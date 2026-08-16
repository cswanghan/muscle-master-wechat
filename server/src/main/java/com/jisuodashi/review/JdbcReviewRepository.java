package com.jisuodashi.review;

import com.jisuodashi.common.JdbcTimes;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("!dev")
public class JdbcReviewRepository implements ReviewRepository {

    private static final RowMapper<Review> ROW = (rs, i) -> new Review(
            rs.getLong("id"),
            rs.getLong("order_id"),
            rs.getLong("customer_id"),
            rs.getLong("therapist_id"),
            rs.getLong("store_id"),
            rs.getInt("score"),
            rs.getString("tags"),
            rs.getString("content"),
            JdbcTimes.instant(rs.getTimestamp("created_at")));

    private static final String COLS =
            "id, order_id, customer_id, therapist_id, store_id, score, tags, content, created_at";

    private final JdbcTemplate jdbc;

    public JdbcReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Review> findByOrderId(long orderId) {
        return jdbc.query(
                "SELECT " + COLS + " FROM review WHERE order_id = ? AND deleted_at IS NULL",
                ROW, orderId).stream().findFirst();
    }

    @Override
    public List<Review> listByTherapistId(long therapistId, int limit) {
        return jdbc.query(
                "SELECT " + COLS + " FROM review WHERE therapist_id = ? AND deleted_at IS NULL"
                        + " ORDER BY created_at DESC LIMIT ?",
                ROW, therapistId, Math.max(1, limit));
    }

    @Override
    public List<Review> listByCustomerId(long customerId) {
        return jdbc.query(
                "SELECT " + COLS + " FROM review WHERE customer_id = ? AND deleted_at IS NULL"
                        + " ORDER BY created_at DESC",
                ROW, customerId);
    }

    @Override
    public Review insert(Review r) {
        jdbc.update(
                "INSERT INTO review (" + COLS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                r.id(), r.orderId(), r.customerId(), r.therapistId(), r.storeId(),
                r.score(), r.tags(), r.content(), Timestamp.from(r.createdAt()));
        return r;
    }

    @Override
    public Optional<Integer> averageScoreX100(long therapistId) {
        Integer avg = jdbc.queryForObject(
                "SELECT CAST(ROUND(AVG(score) * 100) AS SIGNED) FROM review"
                        + " WHERE therapist_id = ? AND deleted_at IS NULL",
                Integer.class, therapistId);
        return Optional.ofNullable(avg);
    }
}
