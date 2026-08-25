package com.jisuodashi.review;

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
public class JdbcReviewStore implements ReviewStore {

    private static final String COLUMNS =
            "id, order_id, customer_id, therapist_id, store_id, score, positive, "
                    + "tags, content, anonymous, status, created_at";

    private static final RowMapper<OrderReview> ROW = (rs, i) -> new OrderReview(
            rs.getLong("id"),
            rs.getLong("order_id"),
            rs.getLong("customer_id"),
            rs.getLong("therapist_id"),
            rs.getLong("store_id"),
            rs.getInt("score"),
            rs.getInt("positive") == 1,
            rs.getString("tags"),
            rs.getString("content"),
            rs.getInt("anonymous") == 1,
            rs.getInt("status"),
            JdbcTimes.instant(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;

    public JdbcReviewStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(OrderReview r) {
        jdbc.update(
                "INSERT INTO order_review (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                r.id(), r.orderId(), r.customerId(), r.therapistId(), r.storeId(),
                r.score(), r.positive() ? 1 : 0, r.tags(), r.content(),
                r.anonymous() ? 1 : 0, r.status(), JdbcTimes.ts(r.createdAt()));
    }

    @Override
    public Optional<OrderReview> findByOrderId(long orderId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM order_review WHERE order_id = ?", ROW, orderId)
                .stream()
                .findFirst();
    }

    @Override
    public List<OrderReview> listSince(Instant since) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM order_review WHERE status = 1 AND created_at >= ?",
                ROW,
                JdbcTimes.ts(since));
    }

    @Override
    public List<OrderReview> listByTherapist(long therapistId, int limit) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM order_review"
                        + " WHERE status = 1 AND therapist_id = ? ORDER BY created_at DESC LIMIT ?",
                ROW,
                therapistId,
                Math.max(1, limit));
    }
}
