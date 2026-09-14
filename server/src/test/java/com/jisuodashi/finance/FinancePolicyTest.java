package com.jisuodashi.finance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FinancePolicyTest {

    @Test
    void payrollIsBasePlusLessonFeePlusSaleCommission() {
        // 底薪 3000 + 30 元/节 × 50 节 + 20000 元销售 × 8%
        long pay = FinancePolicy.payrollFen(300_000, 3_000, 50, 800, 2_000_000);
        assertThat(pay).isEqualTo(300_000 + 150_000 + 160_000);
    }

    @Test
    void negativeLessonsNeverReduceSalary() {
        // 数据异常时工资不能算成负的 —— 报表上一个负数会让人以为要倒扣钱。
        assertThat(FinancePolicy.payrollFen(300_000, 3_000, -5, 800, 0))
                .isEqualTo(300_000);
    }

    @Test
    void inventoryValueIsRemainingTimesUnitPrice() {
        // 库存课是负债：钱收了课没上，随时可能被要求退。
        assertThat(FinancePolicy.inventoryValueFen(7, 30_000)).isEqualTo(210_000);
        assertThat(FinancePolicy.inventoryValueFen(0, 30_000)).isZero();
    }
}
