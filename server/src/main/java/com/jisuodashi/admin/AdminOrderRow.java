package com.jisuodashi.admin;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record AdminOrderRow(
        long id,
        String orderNo,
        long storeId,
        long therapistId,
        String status,
        LocalDate serviceDate,
        LocalDateTime createdAt,
        long payableFen,
        boolean manualWorkflow
) {
    public boolean abnormal() {
        return "ABNORMAL".equals(status) || manualWorkflow;
    }
}
