package com.jisuodashi.card;

import com.jisuodashi.common.JdbcTimes;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("!dev")
public class JdbcCardStore implements CardStore {

    private static final RowMapper<CardModels.Card> CARD = (rs, i) -> new CardModels.Card(
            rs.getLong("id"),
            rs.getString("card_no"),
            rs.getLong("customer_id"),
            rs.getLong("store_id"),
            rs.getLong("principal_fen"),
            rs.getLong("bonus_fen"),
            rs.getInt("status"),
            JdbcTimes.instant(rs.getTimestamp("created_at")),
            JdbcTimes.instant(rs.getTimestamp("updated_at")));

    private static final RowMapper<CardModels.Txn> TXN = (rs, i) -> new CardModels.Txn(
            rs.getLong("id"),
            rs.getLong("card_id"),
            rs.getLong("customer_id"),
            rs.getString("type"),
            rs.getLong("principal_delta_fen"),
            rs.getLong("bonus_delta_fen"),
            (Long) rs.getObject("order_id"),
            (Long) rs.getObject("payment_id"),
            (Long) rs.getObject("seller_therapist_id"),
            rs.getString("request_id"),
            JdbcTimes.instant(rs.getTimestamp("created_at")));

    private static final String TXN_COLS =
            "id, card_id, customer_id, type, principal_delta_fen, bonus_delta_fen,"
                    + " order_id, payment_id, seller_therapist_id, request_id, created_at";

    private final JdbcTemplate jdbc;

    public JdbcCardStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@code FOR UPDATE}：余额是读-改-写，两个并发扣款不加锁会各自读到同一个旧值，
     * 后写的那笔把前一笔的扣减覆盖掉，卡里凭空多出钱。
     */
    @Override
    public Optional<CardModels.Card> findByCustomer(long customerId) {
        return one("SELECT * FROM stored_card WHERE customer_id = ? AND status = 1 FOR UPDATE",
                customerId);
    }

    @Override
    public Optional<CardModels.Card> findById(long cardId) {
        return one("SELECT * FROM stored_card WHERE id = ? FOR UPDATE", cardId);
    }

    @Override
    public void insert(CardModels.Card card) {
        jdbc.update(
                """
                INSERT INTO stored_card
                  (id, card_no, customer_id, store_id, principal_fen, bonus_fen,
                   status, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,
                card.id(), card.cardNo(), card.customerId(), card.storeId(),
                card.principalFen(), card.bonusFen(), card.status(),
                JdbcTimes.ts(card.createdAt()), JdbcTimes.ts(card.updatedAt()));
    }

    @Override
    public void updateBalance(long cardId, long principalFen, long bonusFen, Instant now) {
        jdbc.update(
                "UPDATE stored_card SET principal_fen = ?, bonus_fen = ?, updated_at = ? WHERE id = ?",
                principalFen, bonusFen, JdbcTimes.ts(now), cardId);
    }

    @Override
    public void insertTxn(CardModels.Txn txn) {
        jdbc.update(
                "INSERT INTO card_transaction (" + TXN_COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                txn.id(), txn.cardId(), txn.customerId(), txn.type(),
                txn.principalDeltaFen(), txn.bonusDeltaFen(),
                txn.orderId(), txn.paymentId(), txn.sellerTherapistId(),
                txn.requestId(), JdbcTimes.ts(txn.createdAt()));
    }

    @Override
    public boolean txnExists(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return false;
        }
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(1) FROM card_transaction WHERE request_id = ?", Integer.class, requestId);
        return n != null && n > 0;
    }

    @Override
    public List<CardModels.Txn> listByOrder(long orderId) {
        return jdbc.query(
                "SELECT " + TXN_COLS + " FROM card_transaction WHERE order_id = ?"
                        + " ORDER BY created_at, id",
                TXN, orderId);
    }

    @Override
    public List<CardModels.Txn> listSalesByTherapist(long therapistId, LocalDate from, LocalDate to) {
        return jdbc.query(
                "SELECT " + TXN_COLS + " FROM card_transaction"
                        + " WHERE type = ? AND seller_therapist_id = ?"
                        + " AND created_at >= ? AND created_at < ?"
                        + " ORDER BY created_at DESC, id DESC",
                TXN, CardModels.TYPE_TOPUP, therapistId,
                Timestamp.valueOf(from.atStartOfDay()),
                Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
    }

    @Override
    public List<CardModels.Txn> listByCustomer(long customerId, int limit) {
        return jdbc.query(
                "SELECT " + TXN_COLS + " FROM card_transaction WHERE customer_id = ?"
                        + " ORDER BY created_at DESC, id DESC LIMIT ?",
                TXN, customerId, Math.max(1, limit));
    }

    private Optional<CardModels.Card> one(String sql, Object arg) {
        List<CardModels.Card> rows = jdbc.query(sql, CARD, arg);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }
}
