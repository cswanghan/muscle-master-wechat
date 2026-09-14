package com.jisuodashi.membership;

import com.jisuodashi.common.JdbcTimes;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("!dev")
public class JdbcMembershipStore implements MembershipStore {

    private static final RowMapper<MembershipModels.Profile> PROFILE = (rs, i) ->
            new MembershipModels.Profile(
                    rs.getLong("id"), rs.getLong("customer_id"), rs.getLong("store_id"),
                    (Long) rs.getObject("owner_therapist_id"),
                    rs.getString("core_issue"), rs.getString("remark"),
                    rs.getString("channel"),
                    (Long) rs.getObject("referrer_customer_id"),
                    rs.getString("wx_nickname"),
                    rs.getObject("first_visit_on", LocalDate.class),
                    rs.getObject("converted_on", LocalDate.class),
                    JdbcTimes.instant(rs.getTimestamp("created_at")),
                    JdbcTimes.instant(rs.getTimestamp("updated_at")));

    private static final RowMapper<MembershipModels.Package> PKG = (rs, i) ->
            new MembershipModels.Package(
                    rs.getLong("id"), rs.getLong("customer_id"), rs.getLong("store_id"),
                    rs.getLong("project_id"), rs.getString("title"),
                    rs.getInt("total_sessions"), rs.getInt("used_sessions"),
                    rs.getLong("price_fen"), rs.getLong("unit_price_fen"),
                    (Long) rs.getObject("seller_therapist_id"),
                    rs.getObject("effective_on", LocalDate.class),
                    rs.getObject("expire_on", LocalDate.class),
                    rs.getInt("status"),
                    JdbcTimes.instant(rs.getTimestamp("created_at")),
                    JdbcTimes.instant(rs.getTimestamp("updated_at")));

    private static final RowMapper<MembershipModels.Txn> TXN = (rs, i) ->
            new MembershipModels.Txn(
                    rs.getLong("id"), rs.getLong("member_package_id"), rs.getLong("customer_id"),
                    rs.getLong("store_id"), rs.getString("type"), rs.getInt("delta_sessions"),
                    (Long) rs.getObject("order_id"), (Long) rs.getObject("therapist_id"),
                    rs.getString("request_id"), rs.getString("remark"),
                    JdbcTimes.instant(rs.getTimestamp("created_at")));

    private static final RowMapper<MembershipModels.Plan> PLAN = (rs, i) ->
            new MembershipModels.Plan(
                    rs.getLong("id"), rs.getLong("customer_id"), rs.getLong("store_id"),
                    (Long) rs.getObject("therapist_id"), rs.getString("title"), rs.getString("goal"),
                    rs.getInt("total_sessions"), rs.getInt("done_sessions"),
                    rs.getInt("weekly_frequency"),
                    rs.getObject("start_on", LocalDate.class),
                    rs.getObject("end_on", LocalDate.class),
                    rs.getInt("status"),
                    JdbcTimes.instant(rs.getTimestamp("created_at")),
                    JdbcTimes.instant(rs.getTimestamp("updated_at")));

    private static final String PKG_COLS =
            "id, customer_id, store_id, project_id, title, total_sessions, used_sessions,"
                    + " price_fen, unit_price_fen, seller_therapist_id, effective_on, expire_on,"
                    + " status, created_at, updated_at";
    private static final String TXN_COLS =
            "id, member_package_id, customer_id, store_id, type, delta_sessions,"
                    + " order_id, therapist_id, request_id, remark, created_at";
    private static final String PLAN_COLS =
            "id, customer_id, store_id, therapist_id, title, goal, total_sessions,"
                    + " done_sessions, weekly_frequency, start_on, end_on, status,"
                    + " created_at, updated_at";

    private final JdbcTemplate jdbc;

    public JdbcMembershipStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<MembershipModels.Profile> findProfile(long customerId) {
        List<MembershipModels.Profile> rows = jdbc.query(
                "SELECT * FROM member_profile WHERE customer_id = ?", PROFILE, customerId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public void upsertProfile(MembershipModels.Profile p) {
        jdbc.update("""
                INSERT INTO member_profile
                  (id, customer_id, store_id, channel, referrer_customer_id, wx_nickname,
                   first_visit_on, converted_on, owner_therapist_id, core_issue, remark,
                   created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                  store_id = VALUES(store_id),
                  channel = VALUES(channel),
                  referrer_customer_id = VALUES(referrer_customer_id),
                  wx_nickname = VALUES(wx_nickname),
                  first_visit_on = VALUES(first_visit_on),
                  converted_on = VALUES(converted_on),
                  owner_therapist_id = VALUES(owner_therapist_id),
                  core_issue = VALUES(core_issue),
                  remark = VALUES(remark),
                  updated_at = VALUES(updated_at)
                """,
                p.id(), p.customerId(), p.storeId(), p.channel(), p.referrerCustomerId(),
                p.wxNickname(),
                p.firstVisitOn() == null ? null : Date.valueOf(p.firstVisitOn()),
                p.convertedOn() == null ? null : Date.valueOf(p.convertedOn()),
                p.ownerTherapistId(), p.coreIssue(), p.remark(),
                JdbcTimes.ts(p.createdAt()), JdbcTimes.ts(p.updatedAt()));
    }

    @Override
    public List<MembershipModels.Profile> listProfiles(long storeId, Long ownerTherapistId) {
        if (ownerTherapistId == null) {
            return jdbc.query("SELECT * FROM member_profile WHERE store_id = ? ORDER BY id",
                    PROFILE, storeId);
        }
        return jdbc.query(
                "SELECT * FROM member_profile WHERE store_id = ? AND owner_therapist_id = ? ORDER BY id",
                PROFILE, storeId, ownerTherapistId);
    }

    @Override
    public Optional<MembershipModels.Package> findPackage(long id) {
        // FOR UPDATE：耗课是读-改-写，两个并发扣课不加锁会各自读到同一个 used_sessions。
        List<MembershipModels.Package> rows = jdbc.query(
                "SELECT " + PKG_COLS + " FROM member_package WHERE id = ? FOR UPDATE", PKG, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public void insertPackage(MembershipModels.Package p) {
        jdbc.update("INSERT INTO member_package (" + PKG_COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                p.id(), p.customerId(), p.storeId(), p.projectId(), p.title(),
                p.totalSessions(), p.usedSessions(), p.priceFen(), p.unitPriceFen(),
                p.sellerTherapistId(), Date.valueOf(p.effectiveOn()),
                p.expireOn() == null ? null : Date.valueOf(p.expireOn()),
                p.status(), JdbcTimes.ts(p.createdAt()), JdbcTimes.ts(p.updatedAt()));
    }

    @Override
    public void updatePackage(MembershipModels.Package p) {
        jdbc.update("""
                UPDATE member_package SET used_sessions = ?, status = ?, expire_on = ?, updated_at = ?
                WHERE id = ?
                """,
                p.usedSessions(), p.status(),
                p.expireOn() == null ? null : Date.valueOf(p.expireOn()),
                JdbcTimes.ts(p.updatedAt()), p.id());
    }

    @Override
    public List<MembershipModels.Package> listPackagesByCustomer(long customerId) {
        return jdbc.query("SELECT " + PKG_COLS + " FROM member_package WHERE customer_id = ?"
                + " ORDER BY id DESC", PKG, customerId);
    }

    @Override
    public List<MembershipModels.Package> listPackagesEnding(long storeId, LocalDate from, LocalDate to) {
        // 到期或用完都算"该续了"，两种都进分母。
        return jdbc.query("SELECT " + PKG_COLS + " FROM member_package WHERE store_id = ?"
                        + " AND ((expire_on BETWEEN ? AND ?)"
                        + "   OR (used_sessions >= total_sessions AND updated_at >= ? AND updated_at < ?))"
                        + " ORDER BY id DESC",
                PKG, storeId, Date.valueOf(from), Date.valueOf(to),
                Timestamp.valueOf(from.atStartOfDay()),
                Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
    }

    @Override
    public List<MembershipModels.Package> listPackagesSold(
            long storeId, Long therapistId, LocalDate from, LocalDate to) {
        String sql = "SELECT " + PKG_COLS + " FROM member_package WHERE store_id = ?"
                + " AND created_at >= ? AND created_at < ?"
                + (therapistId == null ? "" : " AND seller_therapist_id = ?")
                + " ORDER BY id DESC";
        Object[] args = therapistId == null
                ? new Object[]{storeId, Timestamp.valueOf(from.atStartOfDay()),
                        Timestamp.valueOf(to.plusDays(1).atStartOfDay())}
                : new Object[]{storeId, Timestamp.valueOf(from.atStartOfDay()),
                        Timestamp.valueOf(to.plusDays(1).atStartOfDay()), therapistId};
        return jdbc.query(sql, PKG, args);
    }

    @Override
    public void insertTxn(MembershipModels.Txn t) {
        jdbc.update("INSERT INTO package_transaction (" + TXN_COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                t.id(), t.memberPackageId(), t.customerId(), t.storeId(), t.type(),
                t.deltaSessions(), t.orderId(), t.therapistId(), t.requestId(), t.remark(),
                JdbcTimes.ts(t.createdAt()));
    }

    @Override
    public boolean txnExists(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return false;
        }
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(1) FROM package_transaction WHERE request_id = ?",
                Integer.class, requestId);
        return n != null && n > 0;
    }

    @Override
    public List<MembershipModels.Txn> listTxnsByPackage(long memberPackageId) {
        return jdbc.query("SELECT " + TXN_COLS + " FROM package_transaction"
                + " WHERE member_package_id = ? ORDER BY created_at DESC, id DESC",
                TXN, memberPackageId);
    }

    @Override
    public List<MembershipModels.Txn> listConsumed(
            long storeId, Long therapistId, LocalDate from, LocalDate to) {
        String sql = "SELECT " + TXN_COLS + " FROM package_transaction"
                + " WHERE store_id = ? AND type = ? AND created_at >= ? AND created_at < ?"
                + (therapistId == null ? "" : " AND therapist_id = ?")
                + " ORDER BY created_at DESC, id DESC";
        Timestamp lo = Timestamp.valueOf(from.atStartOfDay());
        Timestamp hi = Timestamp.valueOf(to.plusDays(1).atStartOfDay());
        Object[] args = therapistId == null
                ? new Object[]{storeId, MembershipModels.TYPE_CONSUME, lo, hi}
                : new Object[]{storeId, MembershipModels.TYPE_CONSUME, lo, hi, therapistId};
        return jdbc.query(sql, TXN, args);
    }

    @Override
    public void upsertPlan(MembershipModels.Plan p) {
        jdbc.update("""
                INSERT INTO training_plan (""" + PLAN_COLS + """
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                  title = VALUES(title), goal = VALUES(goal),
                  total_sessions = VALUES(total_sessions), done_sessions = VALUES(done_sessions),
                  weekly_frequency = VALUES(weekly_frequency), end_on = VALUES(end_on),
                  status = VALUES(status), updated_at = VALUES(updated_at)
                """,
                p.id(), p.customerId(), p.storeId(), p.therapistId(), p.title(), p.goal(),
                p.totalSessions(), p.doneSessions(), p.weeklyFrequency(),
                Date.valueOf(p.startOn()), p.endOn() == null ? null : Date.valueOf(p.endOn()),
                p.status(), JdbcTimes.ts(p.createdAt()), JdbcTimes.ts(p.updatedAt()));
    }

    @Override
    public Optional<MembershipModels.Plan> findPlan(long id) {
        List<MembershipModels.Plan> rows = jdbc.query(
                "SELECT " + PLAN_COLS + " FROM training_plan WHERE id = ?", PLAN, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public List<MembershipModels.Plan> listPlansByCustomer(long customerId) {
        // 进行中的排前面：老师打开会员详情，先要看的是现在在练什么。
        return jdbc.query("SELECT " + PLAN_COLS + " FROM training_plan WHERE customer_id = ?"
                + " ORDER BY (status = 1) DESC, id DESC", PLAN, customerId);
    }
}
