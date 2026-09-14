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

    /**
     * 卖课提成：课包实收金额的 8%。
     *
     * <p>**这与工资表的销售提点是同一笔钱**，不是两笔 —— 工资表里的"销售提成"列
     * 就是它，不会重复发。{@code FinancePolicy.DEFAULT_SALE_RATE_X100} 必须与本值一致，
     * 老师单独配了 payroll_config 的以那边为准。
     *
     * <p>演示值，待运营确认。
     */
    public static final int SALE_COMMISSION_RATE_X100 = 800;

    /** 耗课提成：单次课价的 25%。贵课包带出来的课提成自然高，与客单价挂钩。演示值。 */
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
     * 退课要扣回的卖课提成：按**未耗比例**折算。
     *
     * <p>卖 10 节上了 3 节就退，扣回 70% —— 不扣的话"先卖后退"就是刷提成的口子；
     * 全扣又不公平，已经上掉的那 3 节课是真服务过的。
     *
     * <p>耗课提成不回滚：那几节课确实上了。
     */
    public static long saleCommissionClawbackFen(long priceFen, int totalSessions, int usedSessions) {
        if (totalSessions <= 0) {
            return 0;
        }
        int unused = Math.max(0, totalSessions - Math.max(0, usedSessions));
        long paid = saleCommissionFen(priceFen);
        // 先乘后除：先算比例会把小数截掉，10 节退 7 节时能差出好几块。
        return paid * unused / totalSessions;
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
