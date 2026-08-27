package com.jisuodashi.performance;

import com.jisuodashi.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/t")
public class StaffPerformanceController {

    private final PerformanceService performance;

    public StaffPerformanceController(PerformanceService performance) {
        this.performance = performance;
    }

    /** 技师只能看自己的：技师身份从 JWT 解出来，不接受 therapistId 入参。 */
    @GetMapping("/performance")
    public ApiResponse<PerformanceDtos.Summary> mine(
            @RequestParam(value = "range", required = false) String range) {
        return ApiResponse.ok(performance.mine(range));
    }
}
