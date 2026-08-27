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
        String source,
        boolean designated
) {
    public static final String SOURCE_MINI_C = "MINI_C";
    public static final String SOURCE_WALK_IN = "WALK_IN";

    /** 兼容旧调用：未声明则按"非指定"算，指定加成宁可漏给也不能错给。 */
    public LockNewCommand(
            String requestId, long customerId, long storeId, long therapistId,
            long projectId, LocalDate date, int startSlotNo, String source) {
        this(requestId, customerId, storeId, therapistId, projectId, date, startSlotNo, source, false);
    }

    public LockNewCommand {
        if (source == null || source.isBlank()) {
            source = SOURCE_MINI_C;
        }
    }
}
