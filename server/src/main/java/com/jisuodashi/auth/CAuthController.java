package com.jisuodashi.auth;

import com.jisuodashi.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/c/auth")
public class CAuthController {

    private final CustomerAuthService auth;

    public CAuthController(CustomerAuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/wechat")
    public ApiResponse<AuthDtos.CustomerLoginResponse> wechat(
            @Valid @RequestBody AuthDtos.WeChatLoginRequest request) {
        return ApiResponse.ok(auth.login(request));
    }

    @PostMapping("/bind-phone")
    public ApiResponse<AuthDtos.CustomerLoginResponse> bindPhone(
            @RequestBody(required = false) AuthDtos.BindPhoneRequest request) {
        return ApiResponse.ok(auth.bindPhone(AuthContext.requireCustomer(), request));
    }
}
