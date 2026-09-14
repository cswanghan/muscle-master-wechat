package com.jisuodashi.growth;

import com.jisuodashi.common.ApiResponse;
import com.jisuodashi.rbac.Audited;
import com.jisuodashi.rbac.RequirePerm;
import com.jisuodashi.rbac.StoreScoped;
import com.jisuodashi.scoreboard.ScoreboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** 门店端：好评审核、老师横向对比。 */
@RestController
@RequestMapping("/api/v1/f")
public class FrontGrowthController {

    private final GrowthService growth;
    private final ScoreboardService scoreboard;
    private final com.jisuodashi.common.AppClock clock;
    private final com.jisuodashi.rbac.ScopedStoreResolver stores;

    public FrontGrowthController(
            GrowthService growth, ScoreboardService scoreboard,
            com.jisuodashi.common.AppClock clock,
            com.jisuodashi.rbac.ScopedStoreResolver stores) {
        this.growth = growth;
        this.scoreboard = scoreboard;
        this.clock = clock;
        this.stores = stores;
    }

    @GetMapping("/review-claims")
    @StoreScoped
    @RequirePerm("review:claim")
    public ApiResponse<GrowthDtos.ClaimListResponse> claims(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {
        // 走注入的 AppClock 而不是 LocalDate.now()：演示环境的时钟是可以拨的，
        // 用真实系统时间会让查询窗口跟数据整个错开。
        LocalDate f = GrowthService.parseDate(from, clock.today().withDayOfMonth(1));
        return ApiResponse.ok(growth.listClaims(
                stores.resolve(), status == null || status.isBlank() ? null : status,
                f, GrowthService.parseDate(to, f.withDayOfMonth(f.lengthOfMonth()))));
    }

    @PostMapping("/review-claims/{id}/approve")
    @StoreScoped
    @RequirePerm("review:claim")
    @Audited(action = "CLAIM_APPROVE", resourceType = "THERAPIST")
    public ApiResponse<GrowthDtos.ClaimItem> approve(@PathVariable("id") String id) {
        return ApiResponse.ok(growth.decideClaim(id, true));
    }

    @PostMapping("/review-claims/{id}/reject")
    @StoreScoped
    @RequirePerm("review:claim")
    @Audited(action = "CLAIM_REJECT", resourceType = "THERAPIST")
    public ApiResponse<GrowthDtos.ClaimItem> reject(@PathVariable("id") String id) {
        return ApiResponse.ok(growth.decideClaim(id, false));
    }

    /** 本店老师横向对比：上课、业绩、好评、回访一屏看完。 */
    @GetMapping("/therapist-board")
    @StoreScoped
    @RequirePerm("order:list")
    public ApiResponse<List<GrowthDtos.ScoreboardResponse>> therapistBoard(
            @RequestParam(value = "month", required = false) String month) {
        return ApiResponse.ok(scoreboard.storeBoard(stores.resolve(), month));
    }


}
