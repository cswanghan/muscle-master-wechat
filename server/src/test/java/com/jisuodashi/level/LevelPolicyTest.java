package com.jisuodashi.level;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LevelPolicyTest {

    private static final List<LevelModels.LevelConfig> CONFIGS = List.of(
            new LevelModels.LevelConfig("JUNIOR", 1, "初级", 0, 0, 0, 1),
            new LevelModels.LevelConfig("MIDDLE", 2, "中级", 2000, 30, 8500, 1),
            new LevelModels.LevelConfig("SENIOR", 3, "资深", 4000, 80, 9000, 1),
            new LevelModels.LevelConfig("CHIEF", 4, "首席", 8000, 200, 9500, 1));

    private static LevelModels.LifetimeStat stat(int count, int positive) {
        return new LevelModels.LifetimeStat(1L, count, positive);
    }

    @Test
    void bothThresholdsMustPass() {
        // 条数够但好评率差一点：不提名。
        assertThat(LevelPolicy.eligibleLevel("JUNIOR", stat(40, 33), CONFIGS)).isNull();
        // 好评率高但条数不足：同样不提名。
        assertThat(LevelPolicy.eligibleLevel("JUNIOR", stat(10, 10), CONFIGS)).isNull();
        assertThat(LevelPolicy.eligibleLevel("JUNIOR", stat(40, 35), CONFIGS)).isEqualTo("MIDDLE");
    }

    @Test
    void jumpsToTheHighestQualifyingTier() {
        // 一次攒够两档时直接给最高的那档，不逐级爬。
        assertThat(LevelPolicy.eligibleLevel("JUNIOR", stat(100, 92), CONFIGS)).isEqualTo("SENIOR");
        assertThat(LevelPolicy.eligibleLevel("JUNIOR", stat(220, 210), CONFIGS)).isEqualTo("CHIEF");
    }

    @Test
    void neverProposesTheCurrentOrALowerTier() {
        // 资深技师的数据只够中级门槛时不该被降档 —— P1 只提名晋升。
        assertThat(LevelPolicy.eligibleLevel("SENIOR", stat(40, 35), CONFIGS)).isNull();
        assertThat(LevelPolicy.eligibleLevel("CHIEF", stat(300, 295), CONFIGS)).isNull();
    }

    @Test
    void rateRoundsOnTheDefinedBoundary() {
        // 85% 整点算达标（>=），84.9% 不算。
        assertThat(stat(40, 34).positiveRateX100()).isEqualTo(8500);
        assertThat(LevelPolicy.eligibleLevel("JUNIOR", stat(40, 34), CONFIGS)).isEqualTo("MIDDLE");
        assertThat(LevelPolicy.eligibleLevel("JUNIOR", stat(1000, 849), CONFIGS)).isNull();
    }

    @Test
    void noReviewsMeansNoPromotion() {
        assertThat(stat(0, 0).positiveRateX100()).isZero();
        assertThat(LevelPolicy.eligibleLevel("JUNIOR", stat(0, 0), CONFIGS)).isNull();
    }
}
