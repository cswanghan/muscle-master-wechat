package com.jisuodashi.membership;

import com.jisuodashi.common.ApiResponse;
import com.jisuodashi.rbac.Audited;
import com.jisuodashi.rbac.RequirePerm;
import com.jisuodashi.rbac.StoreScoped;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    @PostMapping
    @StoreScoped
    @RequirePerm("member:manage")
    @Audited(action = "PACKAGE_SELL", resourceType = "MEMBER_PACKAGE")
    public ApiResponse<MembershipDtos.SellPackageResponse> sell(
            @RequestBody MembershipDtos.SellPackageRequest request) {
        return ApiResponse.ok(membership.sellByPhone(request));
    }
}
