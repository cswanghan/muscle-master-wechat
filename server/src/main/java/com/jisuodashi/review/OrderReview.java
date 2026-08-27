package com.jisuodashi.review;

import java.time.Instant;

/**
 * 一单一评。{@code positive} 落库定格而不是读时按 {@code score >= 4} 现算：
 * 以后若把好评门槛改到 5 星，历史技师的好评率不会一夜之间集体跳水。
 */
public record OrderReview(
        long id,
        long orderId,
        long customerId,
        long therapistId,
        long storeId,
        int score,
        boolean positive,
        String tags,
        String content,
        boolean anonymous,
        int status,
        Instant createdAt
) {
}
