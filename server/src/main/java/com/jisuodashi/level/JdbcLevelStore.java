package com.jisuodashi.level;

import com.jisuodashi.common.JdbcTimes;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("!dev")
public class JdbcLevelStore implements LevelStore {

    private static final RowMapper<LevelModels.LevelConfig> CONFIG = (rs, i) ->
            new LevelModels.LevelConfig(
                    rs.getString("level"),
                    rs.getInt("sort_no"),
                    rs.getString("display_name"),
                    rs.getInt("price_delta_fen"),
                    rs.getInt("min_review_count"),
                    rs.getInt("min_positive_rate_x100"),
                    rs.getInt("status"));

    private static final RowMapper<LevelModels.LevelLog> LOG = (rs, i) -> new LevelModels.LevelLog(
            rs.getLong("id"),
            rs.getLong("therapist_id"),
            rs.getString("from_level"),
            rs.getString("to_level"),
            rs.getString("reason"),
            (Long) rs.getObject("operator_id"),
            rs.getString("snapshot_json"),
            rs.getInt("status"),
            JdbcTimes.instant(rs.getTimestamp("created_at")),
            JdbcTimes.instant(rs.getTimestamp("decided_at")));

    private static final String LOG_COLS =
            "id, therapist_id, from_level, to_level, reason, operator_id, snapshot_json,"
                    + " status, created_at, decided_at";

    private final JdbcTemplate jdbc;

    public JdbcLevelStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<LevelModels.LevelConfig> listConfigs() {
        return jdbc.query(
                "SELECT level, sort_no, display_name, price_delta_fen, min_review_count,"
                        + " min_positive_rate_x100, status FROM therapist_level_config"
                        + " WHERE status = 1 ORDER BY sort_no",
                CONFIG);
    }

    @Override
    public List<LevelModels.LevelLog> listByStatus(int status) {
        return jdbc.query(
                "SELECT " + LOG_COLS + " FROM therapist_level_log WHERE status = ?"
                        + " ORDER BY created_at DESC",
                LOG, status);
    }

    @Override
    public Optional<LevelModels.LevelLog> findById(long id) {
        return jdbc.query("SELECT " + LOG_COLS + " FROM therapist_level_log WHERE id = ?", LOG, id)
                .stream().findFirst();
    }

    @Override
    public boolean hasPending(long therapistId, String toLevel) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM therapist_level_log"
                        + " WHERE therapist_id = ? AND to_level = ? AND status = ?",
                Integer.class, therapistId, toLevel, LevelModels.STATUS_PENDING);
        return n != null && n > 0;
    }

    @Override
    public void insert(LevelModels.LevelLog l) {
        jdbc.update(
                "INSERT INTO therapist_level_log (" + LOG_COLS + ")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                l.id(), l.therapistId(), l.fromLevel(), l.toLevel(), l.reason(),
                l.operatorId(), l.snapshotJson(), l.status(),
                Timestamp.from(l.createdAt()),
                l.decidedAt() == null ? null : Timestamp.from(l.decidedAt()));
    }

    @Override
    public void decide(long id, int status, long operatorId, Instant decidedAt) {
        jdbc.update(
                "UPDATE therapist_level_log SET status = ?, operator_id = ?, decided_at = ?"
                        + " WHERE id = ? AND status = ?",
                status, operatorId, Timestamp.from(decidedAt), id, LevelModels.STATUS_PENDING);
    }
}
