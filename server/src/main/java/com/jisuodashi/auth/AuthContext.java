package com.jisuodashi.auth;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;

public final class AuthContext {

    private static final ThreadLocal<JwtPrincipal> HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<ApiException> ERROR = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(JwtPrincipal principal) {
        HOLDER.set(principal);
        ERROR.remove();
    }

    public static void setError(ApiException error) {
        ERROR.set(error);
        HOLDER.remove();
    }

    public static JwtPrincipal get() {
        return HOLDER.get();
    }

    public static JwtPrincipal requireCustomer() {
        ApiException error = ERROR.get();
        if (error != null) {
            throw error;
        }
        JwtPrincipal p = HOLDER.get();
        if (p == null) {
            throw new ApiException(ErrorCodes.UNAUTHORIZED, "未登录");
        }
        if (p.typ() != TokenType.C) {
            throw new ApiException(ErrorCodes.FORBIDDEN, "无功能权限");
        }
        return p;
    }

    public static void clear() {
        HOLDER.remove();
        ERROR.remove();
    }
}
