package com.jisuodashi.card;

import com.jisuodashi.common.ClockConfig;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
@Profile("dev")
public class InMemoryCardStore implements CardStore {

    private final ConcurrentHashMap<Long, CardModels.Card> byId = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<CardModels.Txn> txns = new CopyOnWriteArrayList<>();

    @Override
    public Optional<CardModels.Card> findByCustomer(long customerId) {
        return byId.values().stream()
                .filter(c -> c.customerId() == customerId && c.status() == 1)
                .findFirst();
    }

    @Override
    public Optional<CardModels.Card> findById(long cardId) {
        return Optional.ofNullable(byId.get(cardId));
    }

    @Override
    public void insert(CardModels.Card card) {
        byId.put(card.id(), card);
    }

    @Override
    public void updateBalance(long cardId, long principalFen, long bonusFen, Instant now) {
        byId.computeIfPresent(cardId, (k, c) -> new CardModels.Card(
                c.id(), c.cardNo(), c.customerId(), c.storeId(),
                principalFen, bonusFen, c.status(), c.createdAt(), now));
    }

    @Override
    public void insertTxn(CardModels.Txn txn) {
        txns.add(txn);
    }

    @Override
    public boolean txnExists(String requestId) {
        return requestId != null && txns.stream().anyMatch(t -> requestId.equals(t.requestId()));
    }

    @Override
    public List<CardModels.Txn> listByOrder(long orderId) {
        return txns.stream()
                .filter(t -> t.orderId() != null && t.orderId() == orderId)
                .sorted(Comparator.comparing(CardModels.Txn::createdAt))
                .toList();
    }

    @Override
    public List<CardModels.Txn> listByCustomer(long customerId, int limit) {
        return txns.stream()
                .filter(t -> t.customerId() == customerId)
                .sorted(Comparator.comparing(CardModels.Txn::createdAt)
                        .thenComparingLong(CardModels.Txn::id).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public List<CardModels.Txn> listSalesByTherapist(long therapistId, LocalDate from, LocalDate to) {
        return txns.stream()
                .filter(t -> CardModels.TYPE_TOPUP.equals(t.type()))
                .filter(t -> t.sellerTherapistId() != null && t.sellerTherapistId() == therapistId)
                .filter(t -> {
                    LocalDate d = t.createdAt().atZone(ClockConfig.SHANGHAI).toLocalDate();
                    return !d.isBefore(from) && !d.isAfter(to);
                })
                .sorted(Comparator.comparing(CardModels.Txn::createdAt).reversed())
                .toList();
    }
}
