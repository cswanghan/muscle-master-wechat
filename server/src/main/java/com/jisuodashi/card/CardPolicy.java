package com.jisuodashi.card;

/** 储值口径的唯一出处。 */
public final class CardPolicy {

    private CardPolicy() {
    }

    /** 卡销提成比例：售卡金额（本金，不含赠送）的 4%。演示值，待运营确认。 */
    public static final int SALE_COMMISSION_RATE_X100 = 400;

    /**
     * 先扣赠送再扣本金。反过来会把可退的本金花光、只留下不可退的赠送额，
     * 顾客退卡时就只能退到零 —— 对顾客不利，也不是行业惯例。
     */
    public static CardModels.Deduction split(long principalFen, long bonusFen, long wantFen) {
        if (wantFen <= 0) {
            return CardModels.Deduction.NONE;
        }
        long fromBonus = Math.min(bonusFen, wantFen);
        long fromPrincipal = Math.min(principalFen, wantFen - fromBonus);
        return new CardModels.Deduction(fromPrincipal, fromBonus);
    }

    /** 卡销提成只认本金：赠送额是营销成本，不是收入，给提成等于倒贴。 */
    public static long saleCommissionFen(long principalFen) {
        return principalFen * SALE_COMMISSION_RATE_X100 / 10_000;
    }
}
