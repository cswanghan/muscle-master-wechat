package com.jisuodashi.performance;

import java.util.Map;

/**
 * 提成口径。比例目前写死在这里而不是配置表：这些是**演示值**，正式比例要运营定，
 * 定了之后再落 {@code commission_config} 表 —— 现在建表只会把一组占位数字固化成
 * 看起来像已确认的东西。
 *
 * <p>按等级给比例，和 {@code therapist_level_config} 的晋升是同一套激励：升档即加成。
 */
public final class CommissionPolicy {

    /** 演示值，待运营确认。 */
    private static final Map<String, Integer> RATE_X100 = Map.of(
            "JUNIOR", 3000,
            "MIDDLE", 3500,
            "SENIOR", 4000,
            "CHIEF", 4500);

    private static final int DEFAULT_RATE_X100 = 3000;

    /** 指定技师的加成：顾客点名时额外给的那部分，独立于钟数提成。 */
    public static final int DESIGNATED_BONUS_FEN = 500;

    private CommissionPolicy() {
    }

    /** level 可能为空（历史数据、新建技师未填），Map.of 的 getOrDefault 对 null 会 NPE。 */
    public static int rateX100(String level) {
        if (level == null) {
            return DEFAULT_RATE_X100;
        }
        return RATE_X100.getOrDefault(level, DEFAULT_RATE_X100);
    }

    /** 向下取整到分，不做四舍五入 —— 提成宁可少一分也不能凭空多出一分。 */
    public static long commissionFen(long amountFen, String level) {
        return amountFen * rateX100(level) / 10_000;
    }
}
