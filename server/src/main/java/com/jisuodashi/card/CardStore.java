package com.jisuodashi.card;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CardStore {

    Optional<CardModels.Card> findByCustomer(long customerId);

    Optional<CardModels.Card> findById(long cardId);

    void insert(CardModels.Card card);

    /** 余额落库。调用方已在事务内算好新值。 */
    void updateBalance(long cardId, long principalFen, long bonusFen, java.time.Instant now);

    void insertTxn(CardModels.Txn txn);

    /** 幂等兜底：同一 requestId 已记过就别再动余额。 */
    boolean txnExists(String requestId);

    List<CardModels.Txn> listByOrder(long orderId);

    /** 卡销提成用：某技师某段时间卖出的卡。 */
    List<CardModels.Txn> listSalesByTherapist(long therapistId, LocalDate from, LocalDate to);

    /** C 端流水，最新在前。 */
    List<CardModels.Txn> listByCustomer(long customerId, int limit);
}
