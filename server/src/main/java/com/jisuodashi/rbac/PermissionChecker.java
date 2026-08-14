package com.jisuodashi.rbac;

import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.StaffUser;
import com.jisuodashi.auth.StaffUserRepository;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * Function-permission check outside the annotation path. Used when one endpoint serves two
 * task types that need different codes (leave approval vs refund approval on
 * {@code /f/human-tasks/{id}/approve}).
 */
@Component
public class PermissionChecker {

    private final StaffUserRepository staffUsers;

    public PermissionChecker(StaffUserRepository staffUsers) {
        this.staffUsers = staffUsers;
    }

    public boolean holds(JwtPrincipal principal, String code) {
        if (principal == null || principal.staffId() == null) {
            return false;
        }
        StaffUser staff = staffUsers.findById(principal.staffId()).orElse(null);
        if (staff == null) {
            return false;
        }
        Collection<String> held = staff.getPermissionCodes();
        if (held == null || held.isEmpty()) {
            held = PermissionCatalog.forRoles(staff.getRoleCodes());
        }
        return PermissionCatalog.allows(held, code);
    }

    /** 40301 when the current principal lacks {@code code}. */
    public void require(String code) {
        if (!holds(AuthContext.get(), code)) {
            throw new ApiException(ErrorCodes.FORBIDDEN, "无功能权限");
        }
    }
}
