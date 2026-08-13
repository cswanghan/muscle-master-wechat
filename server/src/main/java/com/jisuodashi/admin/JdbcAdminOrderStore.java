package com.jisuodashi.admin;

import com.jisuodashi.rbac.ScopeAwareJdbc;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Repository
@Profile("!dev")
public class JdbcAdminOrderStore implements AdminOrderStore {

    private static final RowMapper<AdminOrderRow> ROW = (rs, i) -> new AdminOrderRow(
            rs.getLong("id"),
            rs.getString("order_no"),
            rs.getLong("store_id"),
            rs.getLong("therapist_id"),
            rs.getString("status"),
            rs.getObject("service_date", java.time.LocalDate.class),
            rs.getObject("created_at", java.time.LocalDateTime.class),
            rs.getLong("payable_fen"),
            rs.getInt("manual") != 0);

    private static final String SELECT = """
            SELECT o.id, o.order_no, o.store_id, o.therapist_id, o.status, o.service_date,
                   o.created_at, o.payable_fen,
                   EXISTS (SELECT 1 FROM workflow_instance w
                            WHERE w.order_id = o.id AND w.status = 'MANUAL') AS manual
              FROM booking_order o
            """;

    private final ScopeAwareJdbc jdbc;

    public JdbcAdminOrderStore(JdbcTemplate jdbcTemplate) {
        this.jdbc = new ScopeAwareJdbc(jdbcTemplate);
    }

    @Override
    public List<AdminOrderRow> list() {
        return jdbc.query(SELECT, ROW);
    }

    @Override
    public List<AdminOrderRow> listAbnormalFirst(Long storeId, int limit) {
        StringBuilder sql = new StringBuilder(SELECT);
        sql.append("""
                 WHERE (o.status = 'ABNORMAL'
                    OR EXISTS (SELECT 1 FROM workflow_instance w
                                WHERE w.order_id = o.id AND w.status = 'MANUAL'))
                """);
        List<Object> args = new ArrayList<>();
        if (storeId != null) {
            sql.append(" AND o.store_id=?");
            args.add(storeId);
        }
        sql.append(" ORDER BY o.id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), ROW, args.toArray());
    }

    @Override
    public List<AdminOrderRow> listAll(
            Long storeId,
            String status,
            LocalDate from,
            LocalDate to,
            AdminOrderCursors.Cursor cursor,
            int fetch) {
        StringBuilder sql = new StringBuilder(SELECT);
        sql.append(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (storeId != null) {
            sql.append(" AND o.store_id=?");
            args.add(storeId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND o.status=?");
            args.add(status);
        }
        if (from != null) {
            sql.append(" AND o.created_at>=?");
            args.add(Timestamp.valueOf(from.atStartOfDay()));
        }
        if (to != null) {
            sql.append(" AND o.created_at<?");
            args.add(Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
        }
        if (cursor != null) {
            Timestamp ts = Timestamp.valueOf(cursor.createdAt());
            sql.append(" AND (o.created_at<? OR (o.created_at=? AND o.id<?))");
            args.add(ts);
            args.add(ts);
            args.add(cursor.id());
        }
        sql.append(" ORDER BY o.created_at DESC, o.id DESC LIMIT ?");
        args.add(fetch);
        return jdbc.query(sql.toString(), ROW, args.toArray());
    }
}
