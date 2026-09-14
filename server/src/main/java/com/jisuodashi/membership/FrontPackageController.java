package com.jisuodashi.membership;

import com.jisuodashi.common.ApiResponse;
import com.jisuodashi.rbac.Audited;
import com.jisuodashi.rbac.RequirePerm;
import com.jisuodashi.rbac.StoreScoped;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前台卖课。收钱走线下或储值卡，这里只记课时 ——
 * 把买课也接进支付链路是另一件事，先不混在一起。
 */
@RestController
@RequestMapping("/api/v1/f/packages")
public class FrontPackageController {

    private final MembershipService membership;

    public FrontPackageController(MembershipService membership) {
        this.membership = membership;
    }

    /**
     * 退课销卡。按剩余次数 × 单次价退，已上的课留在耗课报表里不动。
     */
    @PostMapping("/{id}/refund")
    @StoreScoped
    @RequirePerm("member:manage")
    @Audited(action = "PACKAGE_REFUND", resourceType = "MEMBER_PACKAGE")
    public ApiResponse<MembershipDtos.RefundPackageResponse> refund(
            @org.springframework.web.bind.annotation.PathVariable("id") String id,
            @RequestBody(required = false) MembershipDtos.SellPackageRequest request,
            @RequestParam(value = "reason", required = false) String reason) {
        return ApiResponse.ok(membership.refundPackage(
                id, request == null ? null : request.requestId(), reason));
    }

    /** 代客预约：会员不会用小程序时由前台/老师代劳。 */
    @PostMapping("/proxy-book")
    @StoreScoped
    @RequirePerm("member:manage")
    @Audited(action = "PROXY_BOOK", resourceType = "BOOKING_ORDER")
    public ApiResponse<MembershipDtos.ProxyBookResponse> proxyBook(
            @RequestBody MembershipDtos.ProxyBookRequest request) {
        return ApiResponse.ok(membership.proxyBook(request));
    }

    @PostMapping
    @StoreScoped
    @RequirePerm("member:manage")
    @Audited(action = "PACKAGE_SELL", resourceType = "MEMBER_PACKAGE")
    public ApiResponse<MembershipDtos.SellPackageResponse> sell(
            @RequestBody MembershipDtos.SellPackageRequest request) {
        return ApiResponse.ok(membership.sellByPhone(request));
    }
}
