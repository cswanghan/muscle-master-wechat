package com.jisuodashi.admin;

import com.jisuodashi.common.ApiResponse;
import com.jisuodashi.rbac.RequirePerm;
import com.jisuodashi.rbac.StoreScoped;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/a/orders")
public class AdminOrderController {

    private final AdminOrderService orders;

    public AdminOrderController(AdminOrderService orders) {
        this.orders = orders;
    }

    @GetMapping
    @StoreScoped
    @RequirePerm("order:list")
    public ApiResponse<AdminDtos.OrderListResponse> list(
            @RequestParam(required = false) String view,
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(orders.list(view, storeId, status, from, to, cursor, limit));
    }
}
