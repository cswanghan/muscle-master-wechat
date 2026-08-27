package com.jisuodashi.employment;

import com.jisuodashi.common.JdbcTimes;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.util.List;

@Repository
@Profile("!dev")
public class JdbcEmploymentStore implements EmploymentStore {

    private static final String COLS =
            "id, staff_id, action, effective_on, reason, operator_id, created_at";

    private static final RowMapper<EmploymentModels.Log> ROW = (rs, i) -> new EmploymentModels.Log(
            rs.getLong("id"),
            rs.getLong("staff_id"),
            rs.getString("action"),
            rs.getObject("effective_on", java.time.LocalDate.class),
            rs.getString("reason"),
            (Long) rs.getObject("operator_id"),
            JdbcTimes.instant(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;

    public JdbcEmploymentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(EmploymentModels.Log log) {
        jdbc.update(
                "INSERT INTO employment_log (" + COLS + ") VALUES (?,?,?,?,?,?,?)",
                log.id(),
                log.staffId(),
                log.action(),
                log.effectiveOn() == null ? null : Date.valueOf(log.effectiveOn()),
                log.reason(),
                log.operatorId(),
                JdbcTimes.ts(log.createdAt()));
    }

    @Override
    public List<EmploymentModels.Log> listByStaff(long staffId) {
        return jdbc.query(
                "SELECT " + COLS + " FROM employment_log WHERE staff_id = ? ORDER BY created_at DESC, id DESC",
                ROW, staffId);
    }

    @Override
    public List<EmploymentModels.Log> listRecent(int limit) {
        return jdbc.query(
                "SELECT " + COLS + " FROM employment_log ORDER BY created_at DESC, id DESC LIMIT ?",
                ROW, Math.max(1, limit));
    }
}
