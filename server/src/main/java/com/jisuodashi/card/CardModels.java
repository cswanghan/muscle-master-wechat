package com.jisuodashi.card;

import java.time.Instant;

public final class CardModels {

    private CardModels() {
    }

    /**
     * 余额分两笔：{@code principalFen} 可退，{@code bonusFen} 不可退。
     * 合成一个数就再也拆不回来，退卡时只能靠猜。
     */
    public record Card(
            long id,
            String cardNo,
            long customerId,
            long storeId,
            long principalFen,
            long bonusFen,
            int status,
            Instant createdAt,
            Instant updatedAt
    ) {
        public long balanceFen() {
            return principalFen + bonusFen;
        }
    }

    public record Txn(
            long id,
            long cardId,
            long customerId,
            String type,
            long principalDeltaFen,
            long bonusDeltaFen,
            Long orderId,
            Long paymentId,
            Long sellerTherapistId,
            String requestId,
            Instant createdAt
    ) {
    }

    /** 一次扣款拆出来的两半，退款按同样的比例还回去。 */
    public record Deduction(long principalFen, long bonusFen) {
        public long totalFen() {
            return principalFen + bonusFen;
        }

        public static final Deduction NONE = new Deduction(0, 0);
    }

    public static final String TYPE_TOPUP = "TOPUP";
    public static final String TYPE_CONSUME = "CONSUME";
    public static final String TYPE_REFUND = "REFUND";
}
