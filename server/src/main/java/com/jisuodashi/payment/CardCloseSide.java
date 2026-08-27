package com.jisuodashi.payment;

import com.jisuodashi.card.CardService;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.order.CardRefundSide;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单没付完就关掉时把储值退回卡里。住在 payment 包而不是 card 包，是因为退回卡的同时
 * 还要把那笔 CARD 收款冲掉 —— 只退余额不冲收款，{@code remainingFen} 会一直认为
 * 这单还有钱能退，对账时对不上。
 */
@Component
public class CardCloseSide implements CardRefundSide {

    private final CardService cards;
    private final PaymentStore payments;
    private final SnowflakeIdGenerator ids;
    private final AppClock clock;

    public CardCloseSide(
            CardService cards, PaymentStore payments, SnowflakeIdGenerator ids, AppClock clock) {
        this.cards = cards;
        this.payments = payments;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * 跑在状态机 CAS 的事务里，但 payment store 有自己的一套行锁，
     * 必须自己 begin/commit —— 不配对的话锁会一直留在 ThreadLocal 上，
     * 下一个 {@code lockByRefundNo} 撞上同一行就永远等下去。
     */
    @Override
    public void returnToCard(long orderId) {
        payments.beginWork();
        try {
            doReturn(orderId);
            payments.commitWork();
        } catch (RuntimeException ex) {
            payments.rollbackWork();
            throw ex;
        }
    }

    private void doReturn(long orderId) {
        List<Payment> cardPays = payments.listByOrderId(orderId).stream()
                .filter(p -> Payment.CHANNEL_CARD.equals(p.channel()) && p.success())
                .toList();
        if (cardPays.isEmpty()) {
            return;
        }
        // 退卡按订单整体退，requestId 用订单号兜幂等：同一单重复关闭不会退第二次。
        cards.refundToCard(orderId, "card-close:" + orderId);

        LocalDateTime now = clock.now();
        for (Payment pay : cardPays) {
            String refundNo = PaymentService.refundNoOf(pay.id());
            Refund existing = payments.lockByRefundNo(refundNo);
            if (existing != null && !existing.failed()) {
                continue;
            }
            payments.insertRefund(new Refund(
                    ids.nextId(), refundNo, pay.id(), orderId, pay.amountFen(),
                    "订单关闭，储值原路退回", Refund.SUCCESS, "CARD", null, now, now));
        }
    }
}
