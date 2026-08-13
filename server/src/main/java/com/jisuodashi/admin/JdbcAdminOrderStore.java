package com.jisuodashi.admin;

import com.jisuodashi.rbac.ScopeAwareJdbc;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

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

    private static final String LIST_SQL = """
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
        return jdbc.query(LIST_SQL, ROW);
    }
}
