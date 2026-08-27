package com.jisuodashi.order;

import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ApiResponse;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.payment.PaymentDtos;
import com.jisuodashi.review.ReviewDtos;
import com.jisuodashi.review.ReviewService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/c/bookings")
public class CBookingController {

    private final BookingService bookings;
    private final ReviewService reviews;

    public CBookingController(BookingService bookings, ReviewService reviews) {
        this.bookings = bookings;
        this.reviews = reviews;
    }

    @GetMapping
    public ApiResponse<BookingDtos.Page<BookingDtos.BookingListItem>> list(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(bookings.list(AuthContext.requireCustomer().subjectId(), cursor, limit));
    }

    @GetMapping("/{id}")
    public ApiResponse<BookingDtos.BookingListItem> get(@PathVariable("id") String id) {
        return ApiResponse.ok(bookings.get(AuthContext.requireCustomer().subjectId(), id));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<BookingDtos.CreateBookingResponse>> create(
            @Valid @RequestBody BookingDtos.CreateBookingRequest request) {
        var data = bookings.create(AuthContext.requireCustomer().subjectId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<BookingDtos.CancelBookingResponse> cancel(
            @PathVariable("id") String id,
            @Valid @RequestBody BookingDtos.CancelBookingRequest request) {
        return ApiResponse.ok(bookings.cancel(AuthContext.requireCustomer().subjectId(), id, request));
    }

    @PostMapping("/{id}/pay")
    public ApiResponse<PaymentDtos.PayResponse> pay(
            @PathVariable("id") String id,
            @Valid @RequestBody BookingDtos.PayRequest request) {
        return ApiResponse.ok(bookings.pay(AuthContext.requireCustomer().subjectId(), parseId(id), request));
    }

    @PostMapping("/{id}/review")
    public ApiResponse<ReviewDtos.ReviewDetail> review(
            @PathVariable("id") String id,
            @Valid @RequestBody ReviewDtos.SubmitReviewRequest request) {
        return ApiResponse.ok(
                reviews.submit(AuthContext.requireCustomer().subjectId(), parseId(id), request));
    }

    @GetMapping("/{id}/review")
    public ApiResponse<ReviewDtos.ReviewDetail> getReview(@PathVariable("id") String id) {
        return ApiResponse.ok(reviews.get(AuthContext.requireCustomer().subjectId(), parseId(id)));
    }

    private static long parseId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "orderId 无效");
        }
    }
}
