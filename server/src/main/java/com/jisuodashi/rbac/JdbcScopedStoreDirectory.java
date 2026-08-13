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

    private final ScopedStoreMapper mapper;
    private final ScopeAwareJdbc jdbc;
    private final Clock clock;

    public JdbcScopedStoreDirectory(ScopedStoreMapper mapper, JdbcTemplate jdbcTemplate, Clock clock) {
        this.mapper = mapper;
        this.jdbc = new ScopeAwareJdbc(jdbcTemplate);
        this.clock = clock;
    }

    @Override
    public List<ScopedStore> list() {
        return mapper.list();
    }

    @Override
    public Optional<ScopedStore> find(long id) {
        ScopedStore row = mapper.find(id);
        if (row != null) {
            return Optional.of(row);
        }
        List<ScopedStore> rows = jdbc.query(ScopedStoreQueries.FIND, ROW, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public void updateStatus(long id, int status) {
        jdbc.update(ScopedStoreQueries.UPDATE_STATUS, status, JdbcTimes.ts(Instant.now(clock)), id);
    }
}
