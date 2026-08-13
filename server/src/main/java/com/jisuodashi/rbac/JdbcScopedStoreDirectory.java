package com.jisuodashi.rbac;

import com.jisuodashi.common.JdbcTimes;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("!dev")
public class JdbcScopedStoreDirectory implements ScopedStoreDirectory {

    private static final RowMapper<ScopedStore> ROW = (rs, i) -> new ScopedStore(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getInt("status"));

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcScopedStoreDirectory(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public List<ScopedStore> list() {
        return jdbc.query("SELECT id, code, name, status FROM store WHERE deleted_at IS NULL ORDER BY id", ROW);
    }

    @Override
    public Optional<ScopedStore> find(long id) {
        List<ScopedStore> rows = jdbc.query(
                "SELECT id, code, name, status FROM store WHERE id=? AND deleted_at IS NULL", ROW, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public void updateStatus(long id, int status) {
        jdbc.update(
                "UPDATE store SET status=?, updated_at=? WHERE id=? AND deleted_at IS NULL",
                status,
                JdbcTimes.ts(Instant.now(clock)),
                id);
    }
}
