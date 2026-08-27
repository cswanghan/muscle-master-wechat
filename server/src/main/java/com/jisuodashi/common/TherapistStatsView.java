package com.jisuodashi.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 技师卡上的 30 天统计快照。放在 {@code common} 而不是 {@code review}，
 * 是为了不让 {@code catalog}/{@code inventory} 反向依赖上层的评价模块 ——
 * 分层是 catalog &lt; inventory &lt; order &lt; review，读侧由下层声明接口、上层实现
 * （和 {@code GrayStores}、{@code ServiceRecordSide} 同一套路）。
 *
 * <p>{@code positiveRateX100} 在样本不足时**整个字段缺席**，而不是下发 0 或 -1 ——
 * 不给客户端把哨兵值当成真实的「好评率 0%」渲染出来的机会。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TherapistStatsView(
        int servedCount,
        int repeatCount,
        int repeatCustomerCount,
        int reviewCount,
        Integer positiveRateX100,
        Integer avgScoreX100,
        boolean newcomer
) {
    /** 无统计行 = 新技师。 */
    public static final TherapistStatsView NONE =
            new TherapistStatsView(0, 0, 0, 0, null, null, true);
}
