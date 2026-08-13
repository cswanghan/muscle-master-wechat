package com.jisuodashi.staff;

import java.time.Instant;

public record ServiceRecord(
        long id,
        long orderId,
        long therapistId,
        long customerId,
        long storeId,
        Instant startedAt,
        Instant endedAt,
        Instant createdAt
) {
}
