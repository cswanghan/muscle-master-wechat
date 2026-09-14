package com.jisuodashi.membership;

import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 客户端：自己的档案、训练计划、课包与可用课时。 */
@RestController
@RequestMapping("/api/v1/c/membership")
public class CustomerMembershipController {

    private final MembershipQueryService query;

    public CustomerMembershipController(MembershipQueryService query) {
        this.query = query;
    }

    @GetMapping
    public ApiResponse<MembershipDtos.MyMembershipResponse> mine() {
        return ApiResponse.ok(query.mine(AuthContext.requireCustomer().customerId()));
    }
}
