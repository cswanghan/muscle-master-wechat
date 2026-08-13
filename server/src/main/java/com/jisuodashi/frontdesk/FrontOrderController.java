package com.jisuodashi.frontdesk;

import com.jisuodashi.common.ApiResponse;
import com.jisuodashi.rbac.RequirePerm;
import com.jisuodashi.rbac.StoreScoped;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/f/orders")
public class FrontOrderController {

    private final FrontDeskService desk;

    public FrontOrderController(FrontDeskService desk) {
        this.desk = desk;
    }

    @GetMapping("/lookup")
    @StoreScoped
    @RequirePerm("frontdesk:order:*")
    public ApiResponse<FrontDeskDtos.LookupResponse> lookup(
            @RequestParam(value = "verify", required = false) String verify,
            @RequestParam(value = "keyword", required = false) String keyword) {
        return ApiResponse.ok(desk.lookup(verify, keyword));
    }

    @PostMapping("/{id}/check-in")
    @StoreScoped
    @RequirePerm("frontdesk:order:*")
    public ApiResponse<FrontDeskDtos.CheckInResponse> checkIn(
            @PathVariable("id") String id,
            @Valid @RequestBody FrontDeskDtos.CheckInRequest request) {
        return ApiResponse.ok(desk.checkIn(id, request));
    }

    @PostMapping("/{id}/refund")
    @StoreScoped
    @RequirePerm("refund:create")
    public ApiResponse<FrontDeskDtos.RefundResponse> refund(
            @PathVariable("id") String id,
            @Valid @RequestBody FrontDeskDtos.RefundRequest request) {
        return ApiResponse.ok(desk.refund(id, request));
    }
}
