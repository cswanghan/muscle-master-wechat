package com.jisuodashi.card;

import com.jisuodashi.auth.Customer;
import com.jisuodashi.auth.CustomerRepository;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.PhoneCrypto;
import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.rbac.StoreScope;
import com.jisuodashi.rbac.StoreScopeContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 储值卡。余额是**负债**不是营收 —— 充值那一刻钱进了账但服务还没发生，
 * 所以这里只管余额与流水，不碰任何营收口径。
 */
@Service
public class CardService {

    private final CardStore cards;
    private final CustomerRepository customers;
    private final PhoneCrypto crypto;
    private final SnowflakeIdGenerator ids;
    private final AppClock clock;
    private final TransactionTemplate tx;

    public CardService(
            CardStore cards,
            CustomerRepository customers,
            PhoneCrypto crypto,
            SnowflakeIdGenerator ids,
            AppClock clock,
            TransactionTemplate tx) {
        this.cards = cards;
        this.customers = customers;
        this.crypto = crypto;
        this.ids = ids;
        this.clock = clock;
        this.tx = tx;
    }

    public Optional<CardModels.Card> myCard(long customerId) {
        return cards.findByCustomer(customerId);
    }

    /** 没开过卡返回全 0，不是 404 —— 前端不该为"还没充过值"写一条分支。 */
    public CardDtos.Wallet wallet(long customerId) {
        CardModels.Card card = cards.findByCustomer(customerId).orElse(null);
        if (card == null) {
            return CardDtos.Wallet.empty();
        }
        List<CardDtos.TxnItem> items = cards.listByCustomer(customerId, 20).stream()
                .map(CardService::toItem)
                .toList();
        return new CardDtos.Wallet(
                card.cardNo(), card.principalFen(), card.bonusFen(), card.balanceFen(), items);
    }

    public CardDtos.Wallet walletByPhone(String phone) {
        return wallet(requireCustomerId(phone));
    }

    /**
     * 前台储值。收钱走线下，这里只记账。{@code sellerTherapistId} 决定卡销提成归谁。
     */
    public CardDtos.TopUpResponse topUpByPhone(CardDtos.TopUpRequest req) {
        long customerId = requireCustomerId(req.phone());
        long storeId = parseId(req.storeId(), operatorStoreId());
        long bonus = req.bonusFen() == null ? 0L : req.bonusFen();
        Long seller = req.sellerTherapistId() == null || req.sellerTherapistId().isBlank()
                ? null : parseId(req.sellerTherapistId(), 0L);
        String requestId = req.requestId() == null || req.requestId().isBlank()
                ? "topup:" + customerId + ":" + Instant.now(clock.clock()).toEpochMilli()
                : req.requestId();
        CardModels.Card card = topUp(customerId, storeId, req.principalFen(), bonus, seller, requestId);
        return new CardDtos.TopUpResponse(
                card.cardNo(),
                String.valueOf(customerId),
                card.principalFen(),
                card.bonusFen(),
                card.balanceFen(),
                CardPolicy.saleCommissionFen(req.principalFen()));
    }

    /**
     * 发卡门店。前台不用填，取操作人数据域里的那家店；超管（ALL）没有"自己的店"，
     * 只能由请求显式给。发卡门店只影响归属统计，不限制在哪家店消费。
     */
    private static long operatorStoreId() {
        StoreScope scope = StoreScopeContext.get();
        if (scope == null || scope.storeIds().isEmpty()) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "请指定发卡门店 storeId");
        }
        return scope.storeIds().getFirst();
    }

    private long requireCustomerId(String phone) {
        String e164 = PhoneCrypto.normalizeCnMobile(phone);
        if (e164 == null) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "手机号格式不对");
        }
        return customers.findByPhoneHash(crypto.hashE164(e164))
                .map(Customer::getId)
                .orElseThrow(() -> new ApiException(
                        ErrorCodes.NOT_FOUND, "该手机号还不是会员，先让顾客在小程序登录一次"));
    }

    private static CardDtos.TxnItem toItem(CardModels.Txn t) {
        long delta = t.principalDeltaFen() + t.bonusDeltaFen();
        return new CardDtos.TxnItem(
                String.valueOf(t.id()),
                t.type(),
                switch (t.type()) {
                    case CardModels.TYPE_TOPUP -> "充值";
                    case CardModels.TYPE_REFUND -> "退款退回";
                    default -> "消费";
                },
                delta,
                t.orderId() == null ? null : String.valueOf(t.orderId()),
                t.createdAt() == null ? null : t.createdAt().toString());
    }

    private static long parseId(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "id 无效");
        }
    }

    /**
     * 充值。{@code bonusFen} 是赠送额，单独记账：退卡只退本金。
     * {@code sellerTherapistId} 决定卡销提成归谁，可空（前台自己卖的）。
     */
    public CardModels.Card topUp(
            long customerId, long storeId, long principalFen, long bonusFen,
            Long sellerTherapistId, String requestId) {
        if (principalFen <= 0) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "充值金额必须大于 0");
        }
        if (bonusFen < 0) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "赠送金额不能为负");
        }
        return tx.execute(status -> {
            if (cards.txnExists(requestId)) {
                // 重放：余额已经加过，再加一次就是白送。
                return cards.findByCustomer(customerId).orElseThrow(
                        () -> new ApiException(ErrorCodes.NOT_FOUND, "卡不存在"));
            }
            Instant now = Instant.now(clock.clock());
            CardModels.Card card = cards.findByCustomer(customerId).orElseGet(() -> {
                CardModels.Card fresh = new CardModels.Card(
                        ids.nextId(), newCardNo(), customerId, storeId, 0, 0, 1, now, now);
                cards.insert(fresh);
                return fresh;
            });
            long principal = card.principalFen() + principalFen;
            long bonus = card.bonusFen() + bonusFen;
            cards.updateBalance(card.id(), principal, bonus, now);
            cards.insertTxn(new CardModels.Txn(
                    ids.nextId(), card.id(), customerId, CardModels.TYPE_TOPUP,
                    principalFen, bonusFen, null, null, sellerTherapistId, requestId, now));
            return cards.findById(card.id()).orElseThrow();
        });
    }

    /**
     * 尽力扣款：卡里有多少扣多少，差额由调用方走微信补。返回实际扣掉的两半。
     * 余额不足**不是错误** —— 混合支付本来就允许卡不够。
     */
    public CardModels.Deduction deduct(long customerId, long orderId, long wantFen, String requestId) {
        if (wantFen <= 0) {
            return CardModels.Deduction.NONE;
        }
        return tx.execute(status -> {
            if (cards.txnExists(requestId)) {
                // 重放：返回当初扣的那笔，不再动余额。
                return cards.listByOrder(orderId).stream()
                        .filter(t -> requestId.equals(t.requestId()))
                        .findFirst()
                        .map(t -> new CardModels.Deduction(-t.principalDeltaFen(), -t.bonusDeltaFen()))
                        .orElse(CardModels.Deduction.NONE);
            }
            CardModels.Card card = cards.findByCustomer(customerId).orElse(null);
            if (card == null || card.balanceFen() <= 0) {
                return CardModels.Deduction.NONE;
            }
            CardModels.Deduction d = CardPolicy.split(card.principalFen(), card.bonusFen(), wantFen);
            if (d.totalFen() <= 0) {
                return CardModels.Deduction.NONE;
            }
            Instant now = Instant.now(clock.clock());
            cards.updateBalance(card.id(),
                    card.principalFen() - d.principalFen(),
                    card.bonusFen() - d.bonusFen(), now);
            cards.insertTxn(new CardModels.Txn(
                    ids.nextId(), card.id(), customerId, CardModels.TYPE_CONSUME,
                    -d.principalFen(), -d.bonusFen(), orderId, null, null, requestId, now));
            return d;
        });
    }

    /**
     * 退款原路退回卡，且按当初扣款的本金 / 赠送比例还回去 —— 全退成本金等于把
     * 赠送额洗成可提现的钱，全退成赠送又亏了顾客。
     */
    public CardModels.Deduction refundToCard(long orderId, String requestId) {
        return tx.execute(status -> {
            if (cards.txnExists(requestId)) {
                return CardModels.Deduction.NONE;
            }
            List<CardModels.Txn> consumed = cards.listByOrder(orderId).stream()
                    .filter(t -> CardModels.TYPE_CONSUME.equals(t.type()))
                    .toList();
            if (consumed.isEmpty()) {
                return CardModels.Deduction.NONE;
            }
            long principal = consumed.stream().mapToLong(t -> -t.principalDeltaFen()).sum();
            long bonus = consumed.stream().mapToLong(t -> -t.bonusDeltaFen()).sum();
            if (principal + bonus <= 0) {
                return CardModels.Deduction.NONE;
            }
            CardModels.Card card = cards.findById(consumed.getFirst().cardId())
                    .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "卡不存在"));
            Instant now = Instant.now(clock.clock());
            cards.updateBalance(card.id(),
                    card.principalFen() + principal, card.bonusFen() + bonus, now);
            cards.insertTxn(new CardModels.Txn(
                    ids.nextId(), card.id(), card.customerId(), CardModels.TYPE_REFUND,
                    principal, bonus, orderId, null, null, requestId, now));
            return new CardModels.Deduction(principal, bonus);
        });
    }

    private String newCardNo() {
        return "C" + Instant.now(clock.clock()).toEpochMilli();
    }
}
