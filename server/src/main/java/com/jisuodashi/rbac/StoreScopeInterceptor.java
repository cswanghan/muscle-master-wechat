package com.jisuodashi.rbac;

import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.TokenType;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;


@Component
public class StoreScopeInterceptor implements HandlerInterceptor {

    private final StoreScopeResolver resolver;
    private final PermissionChecker permissions;

    public StoreScopeInterceptor(StoreScopeResolver resolver, PermissionChecker permissions) {
        this.resolver = resolver;
        this.permissions = permissions;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        String path = request.getServletPath();
        if (StoreScopedWriteScanner.isTherapistPath(path)) {
            JwtPrincipal principal = AuthContext.requireStaff();
            if (principal.typ() != TokenType.T) {
                throw new ApiException(ErrorCodes.FORBIDDEN, "无功能权限");
            }
            return true;
        }
        if (!StoreScopedWriteScanner.isFaPath(path)) {
            return true;
        }
        JwtPrincipal principal = AuthContext.requireStaff();
        assertClientAllowed(path, principal);
        RequirePerm perm = method.getMethodAnnotation(RequirePerm.class);
        if (perm == null) {
            perm = method.getBeanType().getAnnotation(RequirePerm.class);
        }
        if (perm != null) {
            assertPermitted(principal, perm.value());
        }
        boolean storeScoped = method.getMethodAnnotation(StoreScoped.class) != null
                || method.getBeanType().getAnnotation(StoreScoped.class) != null;
        if (StoreScopedWriteScanner.WRITE_METHODS.contains(request.getMethod()) && !storeScoped) {
            throw new ApiException(ErrorCodes.DATA_SCOPE, "写接口缺少 @StoreScoped");
        }
        StoreScope scope = resolver.resolve(principal);
        if (storeScoped && !scope.all() && scope.storeIds().isEmpty()) {
            throw new ApiException(ErrorCodes.DATA_SCOPE, "数据域拒绝");
        }
        StoreScopeContext.set(scope);
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        StoreScopeContext.clear();
    }

    private static void assertClientAllowed(String path, JwtPrincipal principal) {
        if (StoreScopedWriteScanner.isAdminPath(path)) {
            if (principal.typ() != TokenType.A) {
                throw new ApiException(ErrorCodes.FORBIDDEN, "无功能权限");
            }
            return;
        }
        if (principal.typ() != TokenType.F && principal.typ() != TokenType.A) {
            throw new ApiException(ErrorCodes.FORBIDDEN, "无功能权限");
        }
    }

    private void assertPermitted(JwtPrincipal principal, String required) {
        if (!permissions.holds(principal, required)) {
            throw new ApiException(ErrorCodes.FORBIDDEN, "无功能权限");
        }
    }
}
