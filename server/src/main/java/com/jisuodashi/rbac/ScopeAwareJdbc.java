package com.jisuodashi.rbac;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

/** JdbcTemplate facade that runs {@link SqlScopeRewriter} before the driver. */
public final class ScopeAwareJdbc {

    private final JdbcTemplate jdbc;

    public ScopeAwareJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        return jdbc.query(scoped(sql), mapper, args);
    }

    public int update(String sql, Object... args) {
        return jdbc.update(scoped(sql), args);
    }

    public static String scoped(String sql) {
        StoreScope scope = StoreScopeContext.get();
        if (scope == null) {
            return sql;
        }
        return SqlScopeRewriter.rewrite(sql, scope);
    }
}
