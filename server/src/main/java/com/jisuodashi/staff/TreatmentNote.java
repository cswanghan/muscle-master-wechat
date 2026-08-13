package com.jisuodashi.staff;

import java.time.Instant;

public record TreatmentNote(
        long id,
        long orderId,
        long storeId,
        long therapistId,
        long authorStaffId,
        String content,
        Instant createdAt
) {
}
