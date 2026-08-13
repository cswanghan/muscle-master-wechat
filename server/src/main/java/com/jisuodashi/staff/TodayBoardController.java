package com.jisuodashi.staff;

import com.jisuodashi.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/t")
public class TodayBoardController {

    private final TodayBoardService today;

    public TodayBoardController(TodayBoardService today) {
        this.today = today;
    }

    @GetMapping("/today")
    public ApiResponse<StaffDtos.TodayBoard> today() {
        return ApiResponse.ok(today.today());
    }
}
