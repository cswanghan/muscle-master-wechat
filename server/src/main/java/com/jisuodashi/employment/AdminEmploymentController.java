package com.jisuodashi.employment;

import com.jisuodashi.common.ApiResponse;
import com.jisuodashi.rbac.Audited;
import com.jisuodashi.rbac.RequirePerm;
import com.jisuodashi.rbac.StoreScoped;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 员工入离职。离职不删人：历史订单、业绩、评价都指着这个账号。
 *
 * <p>离职接口在有未完成订单时返回 {@code status} 不变 + {@code blocked} 清单，
 * 不抛错——前端要把冲突单列出来给人看，再由人决定是改约还是强制离职。
 */
@RestController
@RequestMapping("/api/v1/a/employment")
public class AdminEmploymentController {

    private final EmploymentService employment;

    public AdminEmploymentController(EmploymentService employment) {
        this.employment = employment;
    }

    @GetMapping("/staff")
    @StoreScoped
    @RequirePerm("staff:manage")
    public ApiResponse<EmploymentDtos.StaffListResponse> staff(
            @RequestParam(value = "status", required = false) String status) {
        return ApiResponse.ok(employment.list(status));
    }

    @GetMapping("/logs")
    @StoreScoped
    @RequirePerm("staff:manage")
    public ApiResponse<EmploymentDtos.LogListResponse> logs(
            @RequestParam(value = "staffId", required = false) String staffId) {
        return ApiResponse.ok(employment.history(staffId));
    }

    @PostMapping("/onboard")
    @StoreScoped
    @RequirePerm("staff:manage")
    @Audited(action = "STAFF_ONBOARD", resourceType = "STAFF")
    public ApiResponse<EmploymentDtos.ActionResponse> onboard(
            @RequestBody EmploymentDtos.OnboardRequest request) {
        return ApiResponse.ok(employment.onboard(request));
    }

    @PostMapping("/{id}/offboard")
    @StoreScoped
    @RequirePerm("staff:manage")
    @Audited(action = "STAFF_OFFBOARD", resourceType = "STAFF")
    public ApiResponse<EmploymentDtos.ActionResponse> offboard(
            @PathVariable("id") String id,
            @RequestBody(required = false) EmploymentDtos.OffboardRequest request) {
        return ApiResponse.ok(employment.offboard(id, request));
    }

    @PostMapping("/{id}/rehire")
    @StoreScoped
    @RequirePerm("staff:manage")
    @Audited(action = "STAFF_REHIRE", resourceType = "STAFF")
    public ApiResponse<EmploymentDtos.ActionResponse> rehire(
            @PathVariable("id") String id,
            @RequestBody(required = false) EmploymentDtos.OffboardRequest request) {
        return ApiResponse.ok(employment.rehire(id, request));
    }
}
