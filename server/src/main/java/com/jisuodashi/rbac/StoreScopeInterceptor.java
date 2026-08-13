package com.jisuodashi.rbac;

import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.StaffUser;
import com.jisuodashi.auth.StaffUserRepository;
import com.jisuodashi.auth.TokenType;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Collection;

@Component
public class StoreScopeInterceptor implements HandlerInterceptor {

    private final StoreScopeResolver resolver;
    private final StaffUserRepository staffUsers;

    public StoreScopeInterceptor(StoreScopeResolver resolver, StaffUserRepository staffUsers) {
        this.resolver = resolver;
        this.staffUsers = staffUsers;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || !StoreScopedWriteScanner.isFaPath(request.getServletPath())) {
            return true;
        }
        JwtPrincipal principal = AuthContext.requireStaff();
        assertClientAllowed(request.getServletPath(), principal);
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
        if (principal.staffId() == null) {
            throw new ApiException(ErrorCodes.FORBIDDEN, "无功能权限");
        }
        StaffUser staff = staffUsers.findById(principal.staffId()).orElse(null);
        if (staff == null) {
            throw new ApiException(ErrorCodes.FORBIDDEN, "无功能权限");
        }
        Collection<String> held = staff.getPermissionCodes();
        if (held == null || held.isEmpty()) {
            held = PermissionCatalog.forRoles(staff.getRoleCodes());
        }
        if (!PermissionCatalog.allows(held, required)) {
            throw new ApiException(ErrorCodes.FORBIDDEN, "无功能权限");
        }
    }
}
