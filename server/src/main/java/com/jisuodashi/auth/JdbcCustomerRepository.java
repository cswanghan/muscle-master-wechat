package com.jisuodashi.auth;

import com.jisuodashi.common.JdbcTimes;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("!dev")
public class JdbcCustomerRepository implements CustomerRepository {

    private static final RowMapper<Customer> ROW = (rs, i) -> {
        Customer c = new Customer();
        c.setId(rs.getLong("id"));
        c.setWxOpenid(rs.getString("wx_openid"));
        c.setWxUnionid(rs.getString("wx_unionid"));
        c.setPhoneCipher(rs.getBytes("phone_cipher"));
        c.setPhoneHash(rs.getString("phone_hash"));
        c.setNickname(rs.getString("nickname"));
        c.setTreatmentConsentAt(JdbcTimes.instant(rs.getTimestamp("treatment_consent_at")));
        c.setCreatedAt(JdbcTimes.instant(rs.getTimestamp("created_at")));
        c.setUpdatedAt(JdbcTimes.instant(rs.getTimestamp("updated_at")));
        c.setDeletedAt(JdbcTimes.instant(rs.getTimestamp("deleted_at")));
        return c;
    };

    private final JdbcTemplate jdbc;

    public JdbcCustomerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Customer> findByOpenid(String openid) {
        return one("SELECT * FROM customer WHERE wx_openid = ? AND deleted_at IS NULL", openid);
    }

    @Override
    public Optional<Customer> findByPhoneHash(String phoneHash) {
        return one("SELECT * FROM customer WHERE phone_hash = ? AND deleted_at IS NULL", phoneHash);
    }

    @Override
    public Optional<Customer> findById(long id) {
        return one("SELECT * FROM customer WHERE id = ? AND deleted_at IS NULL", id);
    }

    @Override
    public List<Customer> lockByIds(Collection<Long> ids) {
        List<Long> list = ids.stream().filter(id -> id != null).distinct().toList();
        if (list.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", list.stream().map(id -> "?").toList());
        return jdbc.query(
                "SELECT * FROM customer WHERE deleted_at IS NULL AND id IN (" + placeholders + ") FOR UPDATE",
                ROW,
                list.toArray());
    }

    @Override
    public Customer insert(Customer customer) {
        jdbc.update(
                """
                INSERT INTO customer
                  (id, wx_openid, wx_unionid, phone_cipher, phone_hash, nickname, avatar_url,
                   no_show_count, treatment_consent_at, created_at, updated_at, deleted_at)
                VALUES (?,?,?,?,?,?,?,0,?,?,?,?)
                """,
                customer.getId(),
                customer.getWxOpenid(),
                customer.getWxUnionid(),
                customer.getPhoneCipher(),
                customer.getPhoneHash(),
                customer.getNickname(),
                null,
                JdbcTimes.ts(customer.getTreatmentConsentAt()),
                JdbcTimes.ts(customer.getCreatedAt()),
                JdbcTimes.ts(customer.getUpdatedAt()),
                JdbcTimes.ts(customer.getDeletedAt()));
        return customer;
    }

    @Override
    public void update(Customer customer) {
        jdbc.update(
                """
                UPDATE customer SET wx_openid=?, wx_unionid=?, phone_cipher=?, phone_hash=?,
                  nickname=?, treatment_consent_at=?, updated_at=?, deleted_at=? WHERE id=?
                """,
                customer.getWxOpenid(),
                customer.getWxUnionid(),
                customer.getPhoneCipher(),
                customer.getPhoneHash(),
                customer.getNickname(),
                JdbcTimes.ts(customer.getTreatmentConsentAt()),
                JdbcTimes.ts(customer.getUpdatedAt()),
                JdbcTimes.ts(customer.getDeletedAt()),
                customer.getId());
    }

    private Optional<Customer> one(String sql, Object arg) {
        List<Customer> rows = jdbc.query(sql, ROW, arg);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }
}
