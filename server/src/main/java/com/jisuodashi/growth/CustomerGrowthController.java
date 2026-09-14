package com.jisuodashi.growth;

import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 客户端：课后作业打卡、类朋友圈、给老师打阶段分。 */
@RestController
@RequestMapping("/api/v1/c")
public class CustomerGrowthController {

    private final GrowthService growth;

    public CustomerGrowthController(GrowthService growth) {
        this.growth = growth;
    }

    @PostMapping("/checkins")
    public ApiResponse<GrowthDtos.CheckinItem> checkin(
            @RequestBody GrowthDtos.CheckinRequest request) {
        return ApiResponse.ok(growth.checkin(AuthContext.requireCustomer().customerId(), request));
    }

    /** {@code scope=mine} 看自己的，缺省看本店广场。 */
    @GetMapping("/checkins")
    public ApiResponse<GrowthDtos.CheckinFeedResponse> feed(
            @RequestParam(value = "scope", required = false) String scope) {
        return ApiResponse.ok(growth.feed(AuthContext.requireCustomer().customerId(), scope));
    }

    @PostMapping("/checkins/{id}/like")
    public ApiResponse<GrowthDtos.CheckinItem> like(@PathVariable("id") String id) {
        return ApiResponse.ok(growth.like(AuthContext.requireCustomer().customerId(), id));
    }

    @PostMapping("/stage-reviews")
    public ApiResponse<GrowthDtos.StageReviewItem> rateTherapist(
            @RequestBody GrowthDtos.StageReviewRequest request) {
        return ApiResponse.ok(growth.rateTherapist(
                AuthContext.requireCustomer().customerId(), request));
    }
}
