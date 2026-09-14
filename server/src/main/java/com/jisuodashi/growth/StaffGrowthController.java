package com.jisuodashi.growth;

import com.jisuodashi.common.ApiResponse;
import com.jisuodashi.rbac.Audited;
import com.jisuodashi.rbac.RequirePerm;
import com.jisuodashi.scoreboard.ScoreboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 老师端：回访、打卡、评估、好评认领、成果台。 */
@RestController
@RequestMapping("/api/v1/t")
public class StaffGrowthController {

    private final GrowthService growth;
    private final ScoreboardService scoreboard;

    public StaffGrowthController(GrowthService growth, ScoreboardService scoreboard) {
        this.growth = growth;
        this.scoreboard = scoreboard;
    }

    // 回访
    @GetMapping("/follow-ups")
    @RequirePerm("followup:write")
    public ApiResponse<GrowthDtos.FollowUpListResponse> followUps(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {
        return ApiResponse.ok(growth.myFollowUps(from, to));
    }

    @PostMapping("/follow-ups")
    @RequirePerm("followup:write")
    @Audited(action = "FOLLOW_UP", resourceType = "CUSTOMER")
    public ApiResponse<GrowthDtos.FollowUpItem> logFollowUp(
            @RequestBody GrowthDtos.FollowUpRequest request) {
        return ApiResponse.ok(growth.logFollowUp(request));
    }

    @PostMapping("/follow-ups/{id}/done")
    @RequirePerm("followup:write")
    @Audited(action = "FOLLOW_UP_DONE", resourceType = "CUSTOMER")
    public ApiResponse<GrowthDtos.FollowUpItem> completeFollowUp(
            @PathVariable("id") String id,
            @RequestBody(required = false) GrowthDtos.FollowUpRequest request) {
        return ApiResponse.ok(growth.completeFollowUp(
                id, request == null
                        ? new GrowthDtos.FollowUpRequest(null, null, null, null, null, null, null)
                        : request));
    }

    // 考勤
    @GetMapping("/attendance")
    @RequirePerm("attendance:self")
    public ApiResponse<GrowthDtos.AttendanceResponse> attendance() {
        return ApiResponse.ok(growth.myAttendance());
    }

    @PostMapping("/attendance/clock-in")
    @RequirePerm("attendance:self")
    public ApiResponse<GrowthDtos.AttendanceResponse> clockIn() {
        return ApiResponse.ok(growth.clock(true));
    }

    @PostMapping("/attendance/clock-out")
    @RequirePerm("attendance:self")
    public ApiResponse<GrowthDtos.AttendanceResponse> clockOut() {
        return ApiResponse.ok(growth.clock(false));
    }

    // 评估与结果比对
    @GetMapping("/members/{customerId}/assessments")
    @RequirePerm("staff:self")
    public ApiResponse<GrowthDtos.AssessmentResponse> assessments(
            @PathVariable("customerId") String customerId) {
        return ApiResponse.ok(growth.assessments(GrowthService.parseId(customerId)));
    }

    @PostMapping("/members/{customerId}/assessments")
    @RequirePerm("staff:self")
    @Audited(action = "ASSESSMENT", resourceType = "CUSTOMER")
    public ApiResponse<GrowthDtos.AssessmentResponse> saveAssessment(
            @PathVariable("customerId") String customerId,
            @RequestBody GrowthDtos.AssessmentRequest request) {
        return ApiResponse.ok(growth.saveAssessment(GrowthService.parseId(customerId), request));
    }

    /** 系统给客户的配合度打分：由打卡率换算，不是老师主观填的。 */
    @PostMapping("/members/{customerId}/compliance")
    @RequirePerm("staff:self")
    public ApiResponse<GrowthDtos.StageReviewItem> compliance(
            @PathVariable("customerId") String customerId,
            @RequestParam(value = "planId", required = false) String planId) {
        return ApiResponse.ok(growth.rateCompliance(
                GrowthService.parseId(customerId),
                planId == null || planId.isBlank() ? null : GrowthService.parseId(planId)));
    }

    @GetMapping("/members/{customerId}/follow-ups")
    @RequirePerm("staff:self")
    public ApiResponse<java.util.List<GrowthDtos.FollowUpItem>> customerFollowUps(
            @PathVariable("customerId") String customerId) {
        return ApiResponse.ok(growth.customerFollowUps(GrowthService.parseId(customerId)));
    }

    // 外部好评认领
    @PostMapping("/review-claims")
    @RequirePerm("review:claim")
    @Audited(action = "REVIEW_CLAIM", resourceType = "THERAPIST")
    public ApiResponse<GrowthDtos.ClaimItem> claim(@RequestBody GrowthDtos.ClaimRequest request) {
        return ApiResponse.ok(growth.claim(request));
    }

    // 成果台与全国排名
    @GetMapping("/scoreboard")
    @RequirePerm("ranking:national")
    public ApiResponse<GrowthDtos.ScoreboardResponse> scoreboard(
            @RequestParam(value = "month", required = false) String month) {
        return ApiResponse.ok(scoreboard.mine(month));
    }
}
