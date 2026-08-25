package com.jisuodashi.review;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class ReviewDtos {

    private ReviewDtos() {
    }

    public record SubmitReviewRequest(
            @NotNull @Min(1) @Max(5) Integer score,
            @Size(max = ReviewPolicy.MAX_TAGS) List<String> tags,
            @Size(max = ReviewPolicy.MAX_CONTENT_LENGTH) String content,
            Boolean anonymous
    ) {
    }

    /** 提交与回显同一形状：小程序评价页读写两态复用一个渲染分支。 */
    public record ReviewDetail(
            String orderId,
            String therapistId,
            int score,
            boolean positive,
            List<String> tags,
            String content,
            boolean anonymous,
            String createdAt
    ) {
    }
}
