package com.jisuodashi.membership;

/**
 * 课包口径的唯一出处。
 *
 * <p>核心是一句话：**卖课是负债，耗课才是收入**。所以老师有两项独立的业绩，
 * 卖出去时记一笔销售提成，课真上完再记一笔耗课提成 —— 合成一个数就分不清
 * "这个月卖得好"和"这个月上得多"，而门店真正吃饭的是后者。
 */
public final class MembershipPolicy {

    private MembershipPolicy() {
    }

    /** 卖课提成：课包实收金额的 8%。演示值，待运营确认。 */
    public static final int SALE_COMMISSION_RATE_X100 = 800;

    /** 耗课提成：单次课价的 25%。演示值，待运营确认。 */
    public static final int CONSUME_COMMISSION_RATE_X100 = 2500;

    /** 默认有效期（天）。0 表示不过期。 */
    public static final int DEFAULT_VALID_DAYS = 365;

    public static long saleCommissionFen(long priceFen) {
        return Math.max(0, priceFen) * SALE_COMMISSION_RATE_X100 / 10_000;
    }

    public static long consumeCommissionFen(long unitPriceFen) {
        return Math.max(0, unitPriceFen) * CONSUME_COMMISSION_RATE_X100 / 10_000;
    }

    /**
     * 单次价。除不尽时向上取整到分，让 单价×次数 ≥ 实收 ——
     * 反过来的话按单价退款会退超，差额由门店倒贴。
     */
    public static long unitPriceFen(long priceFen, int totalSessions) {
        if (totalSessions <= 0) {
            return 0;
        }
        return (priceFen + totalSessions - 1) / totalSessions;
    }
}
