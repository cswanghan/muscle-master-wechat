package com.jisuodashi.auth;

import com.jisuodashi.common.JdbcTimes;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@Profile("!dev")
public class JdbcAuthSessionRepository implements AuthSessionRepository {

    private static final RowMapper<AuthSession> ROW = (rs, i) -> {
        AuthSession s = new AuthSession();
        s.setId(rs.getLong("id"));
        s.setSubjectType(rs.getString("subject_type"));
        s.setSubjectId(rs.getLong("subject_id"));
        s.setTokenHash(rs.getString("token_hash"));
        s.setExpireAt(JdbcTimes.instant(rs.getTimestamp("expire_at")));
        s.setCreatedAt(JdbcTimes.instant(rs.getTimestamp("created_at")));
        return s;
    };

    private final JdbcTemplate jdbc;

    public JdbcAuthSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(AuthSession session) {
        jdbc.update(
                """
                INSERT INTO auth_session (id, subject_type, subject_id, token_hash, expire_at, created_at)
                VALUES (?,?,?,?,?,?)
                """,
                session.getId(),
                session.getSubjectType(),
                session.getSubjectId(),
                session.getTokenHash(),
                JdbcTimes.ts(session.getExpireAt()),
                JdbcTimes.ts(session.getCreatedAt()));
    }

    @Override
    public void reassignCustomer(long fromCustomerId, long toCustomerId) {
        jdbc.update(
                "UPDATE auth_session SET subject_id=? WHERE subject_type='CUSTOMER' AND subject_id=?",
                toCustomerId,
                fromCustomerId);
    }

    @Override
    public List<AuthSession> findBySubject(String subjectType, long subjectId) {
        return jdbc.query(
                "SELECT * FROM auth_session WHERE subject_type=? AND subject_id=?",
                ROW,
                subjectType,
                subjectId);
    }
}
