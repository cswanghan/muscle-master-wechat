package com.jisuodashi.auth;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.SnowflakeIdGenerator;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class StaffAuthService {

    private final WeChatClient weChat;
    private final StaffUserRepository staffUsers;
    private final JwtService jwt;
    private final AuthSessionRepository sessions;
    private final SnowflakeIdGenerator ids;
    private final Clock clock;
    private final AppProperties properties;

    public StaffAuthService(
            WeChatClient weChat,
            StaffUserRepository staffUsers,
            JwtService jwt,
            AuthSessionRepository sessions,
            SnowflakeIdGenerator ids,
            Clock clock,
            AppProperties properties) {
        this.weChat = weChat;
        this.staffUsers = staffUsers;
        this.jwt = jwt;
        this.sessions = sessions;
        this.ids = ids;
        this.clock = clock;
        this.properties = properties;
    }

    public AuthDtos.StaffLoginResponse login(AuthDtos.WeChatLoginRequest request) {
        WeChatSession session = weChat.code2Session(request.code(), WeChatApp.STAFF);
        StaffUser staff = staffUsers.findByWxOpenid(session.openid()).orElse(null);
        if (staff == null && properties.getWechat().isMock() && MockWeChatClient.DEV_STAFF_CODE.equals(request.code())) {
            staff = bindMockStaff(session.openid());
        }
        if (staff == null) {
            throw new ApiException(ErrorCodes.UNAUTHORIZED, "员工未开通或未绑定");
        }
        if (staff.getStatus() != 1 || staff.getDeletedAt() != null) {
            throw new ApiException(ErrorCodes.FORBIDDEN, "账号已停用");
        }
        JwtPrincipal principal = JwtPrincipal.staff(
                staff.getId(), staff.tokenType(), staff.getScopeType(), staff.getStoreIds());
        JwtService.IssuedToken issued = jwt.issue(principal);
        AuthSession authSession = new AuthSession();
        authSession.setId(ids.nextId());
        authSession.setSubjectType("STAFF");
        authSession.setSubjectId(staff.getId());
        authSession.setTokenHash(TokenHasher.sha256(issued.token()));
        authSession.setExpireAt(issued.expireAt());
        authSession.setCreatedAt(Instant.now(clock));
        sessions.insert(authSession);
        List<String> storeIds = staff.getStoreIds().stream().map(String::valueOf).toList();
        return new AuthDtos.StaffLoginResponse(
                issued.token(),
                issued.expiresIn(),
                String.valueOf(staff.getId()),
                staff.tokenType().name(),
                staff.getName(),
                staff.getUsername(),
                staff.getScopeType(),
                storeIds);
    }

    private StaffUser bindMockStaff(String openid) {
        String username = properties.getWechat().getMockStaffUsername();
        StaffUser staff = staffUsers.findByUsername(username).orElseGet(this::createMissingDemoAdmin);
        if (staff.getWxOpenid() == null) {
            staff.setWxOpenid(openid);
            staff.setUpdatedAt(Instant.now(clock));
            staffUsers.update(staff);
        } else if (!openid.equals(staff.getWxOpenid())) {
            throw new ApiException(ErrorCodes.UNAUTHORIZED, "员工未开通或未绑定");
        }
        return staff;
    }

    /** Dev-only: V3 seed may be missing when Flyway is off. */
    private StaffUser createMissingDemoAdmin() {
        Instant now = Instant.now(clock);
        StaffUser staff = new StaffUser();
        staff.setId(DemoStaffIds.ADMIN);
        staff.setUsername("demo.admin");
        staff.setName("演示超管");
        staff.setStatus(1);
        staff.setRoleCodes(List.of("SUPER_ADMIN"));
        staff.setScopeType("ALL");
        staff.setStoreIds(List.of());
        staff.setCreatedAt(now);
        staff.setUpdatedAt(now);
        return staffUsers.insert(staff);
    }
}
