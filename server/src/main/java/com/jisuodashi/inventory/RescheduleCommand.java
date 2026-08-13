package com.jisuodashi.inventory;

import java.time.LocalDate;

public record RescheduleCommand(
        String requestId,
        long orderId,
        LocalDate date,
        int startSlotNo,
        long therapistId,
        Long operatorId
) {
}
