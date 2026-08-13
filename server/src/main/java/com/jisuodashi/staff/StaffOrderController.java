package com.jisuodashi.staff;

import com.jisuodashi.common.ApiResponse;
import com.jisuodashi.rbac.Audited;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/t/orders")
public class StaffOrderController {

    private final StaffOrderService orders;

    public StaffOrderController(StaffOrderService orders) {
        this.orders = orders;
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
