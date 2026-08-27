package com.jisuodashi.card;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public final class CardDtos {

    private CardDtos() {
    }

    /** C 端钱包。没开卡时返回全 0 而不是 404 —— 前端不该为"还没充过值"写一条分支。 */
    public record Wallet(
            String cardNo,
            long principalFen,
            long bonusFen,
            long balanceFen,
            List<TxnItem> txns
    ) {
        public static Wallet empty() {
            return new Wallet(null, 0, 0, 0, List.of());
        }
    }

    public record TxnItem(
            String txnId,
            String type,
            String title,
            long deltaFen,
            String orderId,
            String createdAt
    ) {
    }

    public record TopUpRequest(
            String requestId,
            @NotBlank(message = "phone 不能为空") String phone,
            long principalFen,
            Long bonusFen,
            String storeId,
            String sellerTherapistId
    ) {
    }

    public record TopUpResponse(
            String cardNo,
            String customerId,
            long principalFen,
            long bonusFen,
            long balanceFen,
            long saleCommissionFen
    ) {
    }
}
