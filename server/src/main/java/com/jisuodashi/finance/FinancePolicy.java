package com.jisuodashi.finance;

/** 财务口径的唯一出处。 */
public final class FinancePolicy {

    private FinancePolicy() {
    }

    /** 没单独配薪资的老师走这套默认值。演示值，待运营确认。 */
    public static final long DEFAULT_BASE_SALARY_FEN = 300_000L;
    public static final long DEFAULT_LESSON_FEE_FEN = 3_000L;
    public static final int DEFAULT_SALE_RATE_X100 = 800;

    /**
     * 工资 = 底薪 + 课时费×节数 + 销售额×提点。
     *
     * <p>退费不在这里扣：退费责任要人工判定（{@code liable_therapist_id}），
     * 自动扣钱会把"客户搬家"也算到老师头上。退费报表单独出，由店长决定怎么处理。
     */
    public static long payrollFen(long baseFen, long lessonFeeFen, int lessons,
                                  int saleRateX100, long salesFen) {
        return baseFen + lessonFeeFen * Math.max(0, lessons)
                + salesFen * Math.max(0, saleRateX100) / 10_000;
    }

    /**
     * 库存课价值 = 剩余次数 × 单次价。
     *
     * <p>这是**负债**：钱收了但课还没上，随时可能被要求退。
     * 门店的现金流好看不代表这部分能花。
     */
    public static long inventoryValueFen(int remainingSessions, long unitPriceFen) {
        return (long) Math.max(0, remainingSessions) * Math.max(0, unitPriceFen);
    }
}
