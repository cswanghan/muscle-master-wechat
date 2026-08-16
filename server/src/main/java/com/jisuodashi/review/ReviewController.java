package com.jisuodashi.review;

import com.jisuodashi.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/c")
public class ReviewController {

    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    @PostMapping("/bookings/{orderId}/review")
    public ApiResponse<ReviewDtos.ReviewView> create(
            @PathVariable("orderId") String orderId,
            @Valid @RequestBody ReviewDtos.CreateRequest request) {
        return ApiResponse.ok(reviews.create(orderId, request));
    }

    @GetMapping("/bookings/{orderId}/review")
    public ApiResponse<ReviewDtos.ReviewView> ofOrder(@PathVariable("orderId") String orderId) {
        return ApiResponse.ok(reviews.ofOrder(orderId));
    }

    /** Public: shown when picking a therapist. */
    @GetMapping("/therapists/{therapistId}/reviews")
    public ApiResponse<ReviewDtos.ReviewListResponse> byTherapist(
            @PathVariable("therapistId") String therapistId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.ok(reviews.listByTherapist(therapistId, limit));
    }

    @GetMapping("/reviews")
    public ApiResponse<ReviewDtos.ReviewListResponse> mine() {
        return ApiResponse.ok(reviews.mine());
    }
}
