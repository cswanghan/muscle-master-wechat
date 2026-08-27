package com.jisuodashi.auth;

import com.jisuodashi.common.JdbcTimes;
import com.jisuodashi.common.SnowflakeIdGenerator;
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
    private final SnowflakeIdGenerator ids;

    public JdbcStaffUserRepository(JdbcTemplate jdbc, SnowflakeIdGenerator ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    @Override
    public List<StaffUser> listAll() {
        List<StaffUser> rows = jdbc.query(
                "SELECT * FROM staff_user WHERE deleted_at IS NULL ORDER BY id", ROW);
        rows.forEach(this::hydrate);
        return rows;
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
        syncRoles(staff);
        syncScopes(staff);
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
        syncRoles(staff);
        syncScopes(staff);
    }

    /**
     * 角色和数据域存在关联表里，跟 staff_user 一行不是一回事。整体重写而不是 diff：
     * 一个人的角色最多两三条，省下的比较逻辑不值得换来一次漏删。
     * 空角色一律当"这次不改角色"处理，不当清空：系统里没有零角色的员工，
     * 而一个没 hydrate 过的 StaffUser 传进 update() 时 roleCodes 恰好是空的。
     * 真要停某人的权限走离职（status=0），不是把角色删光。
     */
    private void syncRoles(StaffUser staff) {
        List<String> codes = staff.getRoleCodes();
        if (codes == null || codes.isEmpty()) {
            return;
        }
        jdbc.update("DELETE FROM staff_role WHERE staff_user_id = ?", staff.getId());
        for (String code : codes) {
            List<Long> roleId = jdbc.query(
                    "SELECT id FROM role WHERE code = ?", (rs, i) -> rs.getLong(1), code);
            if (!roleId.isEmpty()) {
                jdbc.update("INSERT INTO staff_role (staff_user_id, role_id) VALUES (?,?)",
                        staff.getId(), roleId.getFirst());
            }
        }
    }

    private void syncScopes(StaffUser staff) {
        String type = staff.getScopeType();
        if (type == null || type.isBlank()) {
            return;
        }
        jdbc.update("DELETE FROM data_scope WHERE staff_user_id = ?", staff.getId());
        List<Long> stores = staff.getStoreIds();
        if (stores == null || stores.isEmpty()) {
            jdbc.update(
                    "INSERT INTO data_scope (id, staff_user_id, scope_type, store_id) VALUES (?,?,?,NULL)",
                    ids.nextId(), staff.getId(), type);
            return;
        }
        for (Long storeId : stores) {
            jdbc.update(
                    "INSERT INTO data_scope (id, staff_user_id, scope_type, store_id) VALUES (?,?,?,?)",
                    ids.nextId(), staff.getId(), type, storeId);
        }
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
        List<String> perms = jdbc.query(
                """
                SELECT DISTINCT p.code FROM staff_role sr
                JOIN role_permission rp ON rp.role_id = sr.role_id
                JOIN permission p ON p.id = rp.permission_id
                WHERE sr.staff_user_id = ?
                """,
                (rs, i) -> rs.getString(1),
                staff.getId());
        staff.setPermissionCodes(new ArrayList<>(perms));
    }
}
