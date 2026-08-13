package com.jisuodashi.staff;

import com.jisuodashi.common.ApiResponse;
import com.jisuodashi.rbac.Audited;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/t/orders")
public class StaffOrderController {

    private final StaffOrderService orders;
    private final TodayBoardService today;

    public StaffOrderController(StaffOrderService orders, TodayBoardService today) {
        this.orders = orders;
        this.today = today;
    }

    @GetMapping("/{orderId}")
    public ApiResponse<StaffDtos.NextJob> job(@PathVariable String orderId) {
        return ApiResponse.ok(today.job(orderId));
    }

    @PostMapping("/{orderId}/start")
    @Audited(action = "START_SERVICE", resourceType = "BOOKING_ORDER")
    public ApiResponse<StaffDtos.OrderActionResponse> start(
            @PathVariable String orderId,
            @Valid @RequestBody StaffDtos.OrderActionRequest request) {
        return ApiResponse.ok(orders.start(orderId, request));
    }

    @PostMapping("/{orderId}/complete")
    @Audited(action = "COMPLETE_SERVICE", resourceType = "BOOKING_ORDER")
    public ApiResponse<StaffDtos.OrderActionResponse> complete(
            @PathVariable String orderId,
            @Valid @RequestBody StaffDtos.OrderActionRequest request) {
        return ApiResponse.ok(orders.complete(orderId, request));
    }
}
