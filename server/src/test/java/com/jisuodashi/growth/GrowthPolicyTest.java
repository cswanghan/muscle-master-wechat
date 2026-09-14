package com.jisuodashi.growth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GrowthPolicyTest {

    @Test
    void complianceScoreSpreadsAcrossTheRangeInsteadOfClusteringAtFive() {
        // 主观打分会全是 5 分，失去区分度；按打卡率换算才能用来说
        // "您这个阶段只完成了六成作业"。
        assertThat(GrowthPolicy.complianceScore(10, 10)).isEqualTo(5);
        assertThat(GrowthPolicy.complianceScore(8, 10)).isEqualTo(4);
        assertThat(GrowthPolicy.complianceScore(6, 10)).isEqualTo(3);
        assertThat(GrowthPolicy.complianceScore(3, 10)).isEqualTo(2);
        assertThat(GrowthPolicy.complianceScore(1, 10)).isEqualTo(1);
    }

    @Test
    void complianceIsZeroWhenNothingWasExpected() {
        // 计划还没开始就打分，分母是 0 —— 不能算成满分也不能崩。
        assertThat(GrowthPolicy.complianceScore(0, 0)).isZero();
    }

    @Test
    void rateNeverDividesByZero() {
        assertThat(GrowthPolicy.rateX100(3, 0)).isZero();
        assertThat(GrowthPolicy.rateX100(1, 3)).isEqualTo(3333);
    }

    @Test
    void encouragementCallsOutTheWeakestNumberRatherThanCheering() {
        // 回访拖后腿时就说回访，不要泛泛地"继续加油"。
        assertThat(GrowthPolicy.encourage(40, 9000, 3000)).contains("回访");
        assertThat(GrowthPolicy.encourage(40, 7000, 9000)).contains("好评率");
        assertThat(GrowthPolicy.encourage(0, 0, 0)).contains("还没开张");
    }
}
