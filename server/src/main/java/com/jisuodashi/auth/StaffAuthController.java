package com.jisuodashi.auth;

import com.jisuodashi.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/staff/auth")
public class StaffAuthController {

    private final StaffAuthService auth;

    public StaffAuthController(StaffAuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/wechat")
    public ApiResponse<AuthDtos.StaffLoginResponse> wechat(
            @Valid @RequestBody AuthDtos.WeChatLoginRequest request) {
        return ApiResponse.ok(auth.login(request));
    }
}
