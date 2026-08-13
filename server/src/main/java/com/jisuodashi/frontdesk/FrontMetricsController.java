package com.jisuodashi.frontdesk;

import com.jisuodashi.common.ApiResponse;
import com.jisuodashi.rbac.RequirePerm;
import com.jisuodashi.rbac.StoreScoped;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/f/metrics")
public class FrontMetricsController {

    private final UtilizationService utilization;

    public FrontMetricsController(UtilizationService utilization) {
        this.utilization = utilization;
    }

    @GetMapping("/utilization")
    @StoreScoped
    @RequirePerm("order:list")
    public ApiResponse<FrontDeskDtos.UtilizationResponse> utilization(
            @RequestParam(value = "date", required = false) String date,
            @RequestParam(value = "storeId", required = false) Long storeId) {
        return ApiResponse.ok(utilization.utilization(date, storeId));
    }
}
