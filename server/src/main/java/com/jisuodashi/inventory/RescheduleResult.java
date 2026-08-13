package com.jisuodashi.inventory;

import java.time.LocalDate;

public record RescheduleResult(
        long orderId,
        String orderNo,
        long holdId,
        long therapistId,
        long bedId,
        long roomId,
        String status,
        LocalDate serviceDate,
        int startSlotNo,
        int endSlotNo,
        int acquireCount,
        int releaseCount,
        int keepCount,
        boolean replay
) {
    public RescheduleResult asReplay() {
        return new RescheduleResult(
                orderId, orderNo, holdId, therapistId, bedId, roomId, status,
                serviceDate, startSlotNo, endSlotNo,
                acquireCount, releaseCount, keepCount, true);
    }
}
