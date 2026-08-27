package com.jisuodashi.card;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CardPolicyTest {

    @Test
    void spendsBonusBeforePrincipal() {
        // 留住可退的本金，先花掉不可退的赠送。
        CardModels.Deduction d = CardPolicy.split(100_000, 20_000, 15_000);
        assertThat(d.bonusFen()).isEqualTo(15_000);
        assertThat(d.principalFen()).isZero();
    }

    @Test
    void fallsThroughToPrincipalOnceBonusIsGone() {
        CardModels.Deduction d = CardPolicy.split(100_000, 20_000, 50_000);
        assertThat(d.bonusFen()).isEqualTo(20_000);
        assertThat(d.principalFen()).isEqualTo(30_000);
        assertThat(d.totalFen()).isEqualTo(50_000);
    }

    @Test
    void takesOnlyWhatIsThereWhenShort() {
        // 余额不足不是错误：差额由微信补，这是混合支付的正常路径。
        CardModels.Deduction d = CardPolicy.split(3_000, 2_000, 19_800);
        assertThat(d.totalFen()).isEqualTo(5_000);
    }

    @Test
    void emptyCardTakesNothing() {
        assertThat(CardPolicy.split(0, 0, 19_800).totalFen()).isZero();
        assertThat(CardPolicy.split(100, 100, 0).totalFen()).isZero();
    }

    @Test
    void saleCommissionIgnoresTheBonus() {
        // 充 1000 送 200 时提成只按 1000 算 —— 赠送是营销成本，给提成等于倒贴。
        assertThat(CardPolicy.saleCommissionFen(100_000)).isEqualTo(4_000);
    }
}
