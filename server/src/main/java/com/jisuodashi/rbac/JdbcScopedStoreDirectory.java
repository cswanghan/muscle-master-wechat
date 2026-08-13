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
    private final JdbcTemplate raw;
    private final Clock clock;

    public JdbcScopedStoreDirectory(ScopedStoreMapper mapper, JdbcTemplate jdbcTemplate, Clock clock) {
        this.mapper = mapper;
        this.jdbc = new ScopeAwareJdbc(jdbcTemplate);
        this.raw = jdbcTemplate;
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

    @Override
    public void insert(ScopedStore store) {
        Instant now = Instant.now(clock);
        jdbc.update(
                ScopedStoreQueries.INSERT,
                store.id(),
                store.code(),
                store.name(),
                store.status(),
                JdbcTimes.ts(now),
                JdbcTimes.ts(now));
    }

    @Override
    public void update(ScopedStore store) {
        jdbc.update(
                ScopedStoreQueries.UPDATE,
                store.name(),
                store.status(),
                JdbcTimes.ts(Instant.now(clock)),
                store.id());
    }

    @Override
    public void softDelete(long id) {
        Instant now = Instant.now(clock);
        jdbc.update(ScopedStoreQueries.SOFT_DELETE, JdbcTimes.ts(now), JdbcTimes.ts(now), id);
    }

    @Override
    public boolean codeTaken(String code) {
        Integer one = raw.query(
                "SELECT 1 FROM store WHERE code=? LIMIT 1",
                rs -> rs.next() ? 1 : null,
                code);
        return one != null;
    }
}
