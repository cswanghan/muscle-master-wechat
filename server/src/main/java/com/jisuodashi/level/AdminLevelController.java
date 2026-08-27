package com.jisuodashi.level;

import com.jisuodashi.common.ApiResponse;
import com.jisuodashi.rbac.Audited;
import com.jisuodashi.rbac.RequirePerm;
import com.jisuodashi.rbac.StoreScoped;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 等级晋升的 admin 出口。扫描是幂等的，重复调只会跳过已有的待确认行，
 * 所以日更 job 和手动触发可以共用一个入口。
 */
@RestController
@RequestMapping("/api/v1/a/therapist-levels")
public class AdminLevelController {

    private final LevelService levels;

    public AdminLevelController(LevelService levels) {
        this.levels = levels;
    }

    @GetMapping("/pending")
    @StoreScoped
    @RequirePerm("catalog:therapist")
    public ApiResponse<LevelDtos.PendingListResponse> pending() {
        return ApiResponse.ok(levels.listPending());
    }

    @PostMapping("/scan")
    @StoreScoped
    @RequirePerm("catalog:therapist")
    @Audited(action = "LEVEL_SCAN", resourceType = "THERAPIST")
    public ApiResponse<LevelDtos.ScanResponse> scan() {
        return ApiResponse.ok(levels.scan());
    }

    @PostMapping("/{id}/confirm")
    @StoreScoped
    @RequirePerm("catalog:therapist")
    @Audited(action = "LEVEL_CONFIRM", resourceType = "THERAPIST")
    public ApiResponse<LevelDtos.DecideResponse> confirm(
            @PathVariable("id") String id,
            @RequestBody(required = false) LevelDtos.DecideRequest request) {
        return ApiResponse.ok(levels.confirm(id));
    }

    @PostMapping("/{id}/reject")
    @StoreScoped
    @RequirePerm("catalog:therapist")
    @Audited(action = "LEVEL_REJECT", resourceType = "THERAPIST")
    public ApiResponse<LevelDtos.DecideResponse> reject(
            @PathVariable("id") String id,
            @RequestBody(required = false) LevelDtos.DecideRequest request) {
        return ApiResponse.ok(levels.reject(id));
    }
}
