package com.jisuodashi.membership;

import com.jisuodashi.common.ApiResponse;
import com.jisuodashi.rbac.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 老师端：我的会员、当日时间表。
 *
 * <p>走 {@code staff:self} 而不是 {@code member:manage} —— 老师看自己名下的会员
 * 是本职工作的一部分，不该跟"卖课、改档案"共用一个权限。
 */
@RestController
@RequestMapping("/api/v1/t")
public class StaffMemberController {

    private final MembershipQueryService query;
    private final MembershipService membership;

    public StaffMemberController(MembershipQueryService query, MembershipService membership) {
        this.query = query;
        this.membership = membership;
    }

    @GetMapping("/members")
    @RequirePerm("staff:self")
    public ApiResponse<MembershipDtos.MemberListResponse> members(
            @RequestParam(value = "scope", required = false) String scope) {
        return ApiResponse.ok(query.myMembers(scope));
    }

    @GetMapping("/members/{customerId}")
    @RequirePerm("staff:self")
    public ApiResponse<MembershipDtos.MemberDetail> detail(
            @PathVariable("customerId") String customerId) {
        return ApiResponse.ok(query.detail(customerId));
    }

    @GetMapping("/day-slots")
    @RequirePerm("staff:self")
    public ApiResponse<MembershipDtos.DaySlotsResponse> daySlots(
            @RequestParam(value = "date", required = false) String date,
            @RequestParam(value = "projectId", required = false) String projectId) {
        return ApiResponse.ok(query.daySlots(date, projectId));
    }

    /** 档案编辑。核心问题由带课的老师填最准，所以这条开在老师端而不是只给前台。 */
    @PostMapping("/members/{customerId}/profile")
    @RequirePerm("staff:self")
    public ApiResponse<MembershipDtos.MemberDetail> saveProfile(
            @PathVariable("customerId") String customerId,
            @RequestBody MembershipDtos.ProfileRequest request) {
        membership.saveProfileFromStaff(customerId, request);
        return ApiResponse.ok(query.detail(customerId));
    }
}
