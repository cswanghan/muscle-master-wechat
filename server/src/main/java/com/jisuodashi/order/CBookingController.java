package com.jisuodashi.order;

import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ApiResponse;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.payment.PaymentDtos;
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

    public CBookingController(BookingService bookings) {
        this.bookings = bookings;
    }

    @GetMapping
    public ApiResponse<BookingDtos.Page<BookingDtos.BookingListItem>> list(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(bookings.list(AuthContext.requireCustomer().subjectId(), cursor, limit));
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

    private static long parseId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "orderId 无效");
        }
    }
}
