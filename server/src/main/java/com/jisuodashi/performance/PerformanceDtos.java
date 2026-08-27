package com.jisuodashi.performance;

import java.util.List;

public final class PerformanceDtos {

    private PerformanceDtos() {
    }

    /** 一笔业绩条目。{@code amountFen} 为负表示退款回滚。 */
    public record Entry(
            String orderId,
            String orderNo,
            String kind,
            String title,
            String date,
            String start,
            long amountFen,
            long commissionFen,
            boolean designated
    ) {
    }

    public record Breakdown(
            long serviceFen,
            long addOnFen,
            long designatedFen,
            long refundFen,
            long cardFen
    ) {
    }

    public record Summary(
            String range,
            String from,
            String to,
            String level,
            int rateX100,
            int clockCount,
            long totalCommissionFen,
            Breakdown breakdown,
            List<Entry> entries
    ) {
    }
}
