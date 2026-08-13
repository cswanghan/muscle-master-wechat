package com.jisuodashi.auth;

import com.jisuodashi.common.JdbcTimes;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("!dev")
public class JdbcStaffUserRepository implements StaffUserRepository {

    private static final RowMapper<StaffUser> ROW = (rs, i) -> {
        StaffUser s = new StaffUser();
        s.setId(rs.getLong("id"));
        s.setUsername(rs.getString("username"));
        s.setName(rs.getString("name"));
        s.setPhoneCipher(rs.getBytes("phone_cipher"));
        s.setPhoneHash(rs.getString("phone_hash"));
        s.setWxOpenid(rs.getString("wx_openid"));
        s.setStatus(rs.getInt("status"));
        s.setCreatedAt(JdbcTimes.instant(rs.getTimestamp("created_at")));
        s.setUpdatedAt(JdbcTimes.instant(rs.getTimestamp("updated_at")));
        s.setDeletedAt(JdbcTimes.instant(rs.getTimestamp("deleted_at")));
        return s;
    };

    private final JdbcTemplate jdbc;

    public JdbcStaffUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StaffUser> findByWxOpenid(String openid) {
        return one("SELECT * FROM staff_user WHERE wx_openid = ? AND deleted_at IS NULL", openid);
    }

    @Override
    public Optional<StaffUser> findByUsername(String username) {
        return one("SELECT * FROM staff_user WHERE username = ? AND deleted_at IS NULL", username);
    }

    @Override
    public Optional<StaffUser> findById(long id) {
        return one("SELECT * FROM staff_user WHERE id = ? AND deleted_at IS NULL", id);
    }

    @Override
    public StaffUser insert(StaffUser staff) {
        jdbc.update(
                """
                INSERT INTO staff_user
                  (id, username, password_hash, name, phone_cipher, phone_hash, wx_openid,
                   status, created_at, updated_at, deleted_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,
                staff.getId(),
                staff.getUsername(),
                null,
                staff.getName(),
                staff.getPhoneCipher(),
                staff.getPhoneHash(),
                staff.getWxOpenid(),
                staff.getStatus(),
                JdbcTimes.ts(staff.getCreatedAt()),
                JdbcTimes.ts(staff.getUpdatedAt()),
                JdbcTimes.ts(staff.getDeletedAt()));
        return staff;
    }

    @Override
    public void update(StaffUser staff) {
        jdbc.update(
                """
                UPDATE staff_user SET username=?, name=?, phone_cipher=?, phone_hash=?,
                  wx_openid=?, status=?, updated_at=?, deleted_at=? WHERE id=?
                """,
                staff.getUsername(),
                staff.getName(),
                staff.getPhoneCipher(),
                staff.getPhoneHash(),
                staff.getWxOpenid(),
                staff.getStatus(),
                JdbcTimes.ts(staff.getUpdatedAt()),
                JdbcTimes.ts(staff.getDeletedAt()),
                staff.getId());
    }

    private Optional<StaffUser> one(String sql, Object arg) {
        List<StaffUser> rows = jdbc.query(sql, ROW, arg);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        StaffUser staff = rows.getFirst();
        hydrate(staff);
        return Optional.of(staff);
    }

    private void hydrate(StaffUser staff) {
        List<String> roles = jdbc.query(
                """
                SELECT r.code FROM staff_role sr
                JOIN role r ON r.id = sr.role_id
                WHERE sr.staff_user_id = ?
                """,
                (rs, i) -> rs.getString(1),
                staff.getId());
        staff.setRoleCodes(new ArrayList<>(roles));
        List<String> scopes = jdbc.query(
                "SELECT scope_type FROM data_scope WHERE staff_user_id = ?",
                (rs, i) -> rs.getString(1),
                staff.getId());
        if (!scopes.isEmpty()) {
            staff.setScopeType(scopes.getFirst());
        }
        List<Long> stores = jdbc.query(
                "SELECT store_id FROM data_scope WHERE staff_user_id = ? AND store_id IS NOT NULL",
                (rs, i) -> rs.getLong(1),
                staff.getId());
        staff.setStoreIds(new ArrayList<>(stores));
    }
}
