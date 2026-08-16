package com.jisuodashi.review;

import java.time.Instant;

/** One review per completed order; {@code score} is 1–5. */
public record Review(
        long id,
        long orderId,
        long customerId,
        long therapistId,
        long storeId,
        int score,
        String tags,
        String content,
        Instant createdAt
) {
}
