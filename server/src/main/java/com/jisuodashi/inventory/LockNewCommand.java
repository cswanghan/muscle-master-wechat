package com.jisuodashi.inventory;

import java.time.LocalDate;

public record LockNewCommand(
        String requestId,
        long customerId,
        long storeId,
        long therapistId,
        long projectId,
        LocalDate date,
        int startSlotNo,
        String source
) {
    public static final String SOURCE_MINI_C = "MINI_C";

    public LockNewCommand {
        if (source == null || source.isBlank()) {
            source = SOURCE_MINI_C;
        }
    }
}
