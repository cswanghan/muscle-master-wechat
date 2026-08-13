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
@RequestMapping("/api/v1/f/human-tasks")
public class FrontHumanTaskController {

    private final FrontDeskService desk;

    public FrontHumanTaskController(FrontDeskService desk) {
        this.desk = desk;
    }

    @GetMapping
    @StoreScoped
    @RequirePerm("order:view")
    public ApiResponse<FrontDeskDtos.HumanTaskListResponse> list(
            @RequestParam(value = "status", required = false) String status) {
        return ApiResponse.ok(desk.listHumanTasks(status));
    }

    @PostMapping("/{id}/approve")
    @StoreScoped
    @RequirePerm("refund:approve")
    public ApiResponse<FrontDeskDtos.RefundResponse> approve(
            @PathVariable("id") String id,
            @Valid @RequestBody(required = false) FrontDeskDtos.ApproveRequest request) {
        return ApiResponse.ok(desk.approveTask(id, request));
    }
}
