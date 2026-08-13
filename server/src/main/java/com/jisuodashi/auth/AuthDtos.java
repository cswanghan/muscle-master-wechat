package com.jisuodashi.auth;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record WeChatLoginRequest(
            @NotBlank(message = "code 不能为空") String code,
            String phoneCode
    ) {
    }

    public record BindPhoneRequest(String phoneCode, String phone) {
    }

    public record CustomerLoginResponse(
            String token,
            int expiresIn,
            String customerId,
            boolean needPhone
    ) {
    }

    public record StaffLoginResponse(
            String token,
            int expiresIn,
            String staffId,
            String typ,
            String name,
            String username,
            String scopeType,
            List<String> storeIds
    ) {
    }
}
