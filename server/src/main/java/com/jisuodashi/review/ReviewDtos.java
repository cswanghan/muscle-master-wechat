package com.jisuodashi.review;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public final class ReviewDtos {

    private ReviewDtos() {
    }

    public record CreateRequest(
            String requestId,
            @NotNull(message = "score 不能为空")
            @Min(value = 1, message = "score 需在 1–5")
            @Max(value = 5, message = "score 需在 1–5") Integer score,
            List<String> tags,
            String content
    ) {
    }

    public record ReviewView(
            String reviewId,
            String orderId,
            String therapistId,
            String therapistName,
            int score,
            List<String> tags,
            String content,
            String customerMask,
            String createdAt
    ) {
    }

    public record ReviewListResponse(List<ReviewView> items, Integer avgScoreX100, int total) {
    }
}
