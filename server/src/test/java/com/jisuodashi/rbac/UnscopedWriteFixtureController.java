package com.jisuodashi.rbac;

import com.jisuodashi.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only: missing {@link StoreScoped} so the interceptor can reject it. */
@RestController
@RequestMapping("/api/v1/f/_fixture")
public class UnscopedWriteFixtureController {

    @PostMapping("/unscoped")
    public ApiResponse<String> write() {
        return ApiResponse.ok("leaked");
    }
}
