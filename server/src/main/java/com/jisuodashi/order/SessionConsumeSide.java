package com.jisuodashi.order;

/**
 * {@code CONSUME_SESSION} side：课上完了，从会员课包里扣一次。
 *
 * <p>扣在 {@code COMPLETE_SERVICE} 而不是下单时，因为"耗课"的定义就是课真的上完了 ——
 * 下单就扣的话，改期和取消都要回滚课时，而中途没来算不算耗课也说不清。
 *
 * <p>和 {@link ServiceRecordSide} 一样必须跑在 CAS 的同一个事务里。
 */
public interface SessionConsumeSide {

    void consume(long orderId);
}
