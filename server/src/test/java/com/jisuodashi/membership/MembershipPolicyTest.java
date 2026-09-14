package com.jisuodashi.membership;

import com.jisuodashi.finance.FinancePolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 提成口径。这些数直接决定发多少钱，每一条都要能说清为什么。 */
class MembershipPolicyTest {

    @Test
    void saleCommissionMatchesPayrollRateSoItIsNeverPaidTwice() {
        // 卖课提成与工资表的销售提点是**同一笔钱**。两个常量一旦漂移，
        // 老师就会在课包流水和工资表里各拿一次。
        assertThat(MembershipPolicy.SALE_COMMISSION_RATE_X100)
                .isEqualTo(FinancePolicy.DEFAULT_SALE_RATE_X100);
    }

    @Test
    void clawbackIsProportionalToUnusedSessions() {
        // 3000 元课包 10 节，卖课提成 240 元。上了 3 节就退 → 扣回 70% = 168 元。
        long paid = MembershipPolicy.saleCommissionFen(300_000);
        assertThat(paid).isEqualTo(24_000);
        assertThat(MembershipPolicy.saleCommissionClawbackFen(300_000, 10, 3))
                .isEqualTo(16_800);
    }

    @Test
    void oneLessonUsedStillClawsBackAlmostEverything() {
        // 卖完就退是刷提成的口子，必须堵死：上 1 节退 9 节，扣回 90%。
        assertThat(MembershipPolicy.saleCommissionClawbackFen(300_000, 10, 1))
                .isEqualTo(21_600);
    }

    @Test
    void fullyConsumedPackageClawsBackNothing() {
        // 课上完了才退（比如过期清理），没有未耗部分，不该扣老师的钱。
        assertThat(MembershipPolicy.saleCommissionClawbackFen(300_000, 10, 10)).isZero();
    }

    @Test
    void clawbackMultipliesBeforeDividingToAvoidLosingCents() {
        // 先算比例再乘会把小数截掉。10 节退 7 节、提成 24000 分：
        // 正确 16800；先除后乘会得到 16800 以下的值。
        assertThat(MembershipPolicy.saleCommissionClawbackFen(300_000, 10, 3))
                .isEqualTo(24_000L * 7 / 10);
    }

    @Test
    void consumeCommissionScalesWithUnitPrice() {
        // 耗课提成按单次课价的 25% —— 贵课包带出来的课，提成自然高。
        assertThat(MembershipPolicy.consumeCommissionFen(30_000)).isEqualTo(7_500);
        assertThat(MembershipPolicy.consumeCommissionFen(60_000)).isEqualTo(15_000);
    }

    @Test
    void unitPriceRoundsUpSoRefundNeverOverpays() {
        // 1000 元 3 次除不尽：单价向上取整，保证 单价×次数 ≥ 实收。
        long unit = MembershipPolicy.unitPriceFen(100_000, 3);
        assertThat(unit * 3).isGreaterThanOrEqualTo(100_000);
    }
}
