package com.jisuodashi.order;

import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/c/bookings")
public class CBookingController {

    private final BookingService bookings;

    public CBookingController(BookingService bookings) {
        this.bookings = bookings;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<BookingDtos.CreateBookingResponse>> create(
            @Valid @RequestBody BookingDtos.CreateBookingRequest request) {
        var data = bookings.create(AuthContext.requireCustomer().subjectId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data));
    }
}
