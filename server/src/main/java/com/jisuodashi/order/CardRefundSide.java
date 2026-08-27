package com.jisuodashi.order;

/**
 * {@code RETURN_CARD} side：订单还没付完就关掉时，把已经扣掉的储值退回卡里。
 *
 * <p>混合支付下卡是先扣的、微信是后付的，中间这段时间订单可能因为超时或用户取消而关闭。
 * 不退回来，那笔钱就卡在一张永远不会发生的单子上，顾客只能找客服。
 *
 * <p>和 {@link ServiceRecordSide} 一样必须跑在 CAS 的同一个事务里。
 */
public interface CardRefundSide {

    void returnToCard(long orderId);
}
