package com.jisuodashi.performance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommissionPolicyTest {

    @Test
    void rateFollowsLevel() {
        assertThat(CommissionPolicy.rateX100("JUNIOR")).isEqualTo(3000);
        assertThat(CommissionPolicy.rateX100("CHIEF")).isEqualTo(4500);
    }

    @Test
    void unknownLevelFallsBackToTheLowestRate() {
        // 脏数据不该意外拿到高提成。
        assertThat(CommissionPolicy.rateX100(null)).isEqualTo(3000);
        assertThat(CommissionPolicy.rateX100("WHATEVER")).isEqualTo(3000);
    }

    @Test
    void roundsDownSoCommissionNeverExceedsTheRate() {
        assertThat(CommissionPolicy.commissionFen(19_800, "MIDDLE")).isEqualTo(6_930);
        // 12801 * 35% = 4480.35 → 4480，宁可少一分也不多一分
        assertThat(CommissionPolicy.commissionFen(12_801, "MIDDLE")).isEqualTo(4_480);
    }

    @Test
    void zeroAmountEarnsNothing() {
        assertThat(CommissionPolicy.commissionFen(0, "CHIEF")).isZero();
    }
}
