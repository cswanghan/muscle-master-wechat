package com.jisuodashi.admin;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ApiResponse;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.inventory.ScheduleExceptionDtos;
import com.jisuodashi.inventory.ScheduleExceptionService;
import com.jisuodashi.rbac.RequirePerm;
import com.jisuodashi.rbac.StoreScoped;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * §2.3 leave/support exceptions. {@code approve} is the same method the store manager reaches
 * through {@code /f/human-tasks/{id}/approve} — this is not a second state machine.
 */
@RestController
@RequestMapping("/api/v1/a/schedule-exceptions")
public class AdminScheduleExceptionController {

    private final ScheduleExceptionService exceptions;

    public AdminScheduleExceptionController(ScheduleExceptionService exceptions) {
        this.exceptions = exceptions;
    }

    @GetMapping
    @StoreScoped
    @RequirePerm("schedule:write")
    public ApiResponse<ScheduleExceptionDtos.ListResponse> list(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(exceptions.list(from, to, status));
    }

    @PostMapping
    @StoreScoped
    @RequirePerm("schedule:write")
    public ResponseEntity<ApiResponse<ScheduleExceptionDtos.ExceptionView>> apply(
            @RequestBody ScheduleExceptionDtos.ApplyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(exceptions.apply(request)));
    }

    @PostMapping("/{id}/approve")
    @StoreScoped
    @RequirePerm("schedule:approve")
    public ApiResponse<ScheduleExceptionDtos.ExceptionView> approve(
            @PathVariable String id, @RequestBody(required = false) ScheduleExceptionDtos.DecisionRequest body) {
        return ApiResponse.ok(exceptions.approve(parseId(id)));
    }

    @PostMapping("/{id}/reject")
    @StoreScoped
    @RequirePerm("schedule:approve")
    public ApiResponse<ScheduleExceptionDtos.ExceptionView> reject(
            @PathVariable String id, @RequestBody(required = false) ScheduleExceptionDtos.DecisionRequest body) {
        return ApiResponse.ok(exceptions.reject(parseId(id)));
    }

    private static long parseId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "id 无效");
        }
    }
}
