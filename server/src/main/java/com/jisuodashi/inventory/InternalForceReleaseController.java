package com.jisuodashi.inventory;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ApiResponse;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.job.ForceReleaseJob;
import com.jisuodashi.rbac.AuditHints;
import com.jisuodashi.rbac.Audited;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Rollback drill. Off unless {@code app.internal.force-release.enabled} plus a
 * shared token; also loopback-only. Not under /api/v1/c|t|f|a.
 */
@RestController
@RequestMapping("/internal")
public class InternalForceReleaseController {

    public static final String TOKEN_HEADER = "X-Internal-Token";

    private final ForceReleaseJob forceReleaseJob;
    private final AppProperties properties;

    public InternalForceReleaseController(ForceReleaseJob forceReleaseJob, AppProperties properties) {
        this.forceReleaseJob = forceReleaseJob;
        this.properties = properties;
    }

    @PostMapping("/force-release")
    @Audited(action = "FORCE_RELEASE", resourceType = "HOLD")
    public ApiResponse<ReleaseResult> forceRelease(
            @RequestParam("holdId") long holdId,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            HttpServletRequest request
    ) {
        authorize(request, token);
        AuditHints.setResourceId(holdId);
        ReleaseResult result = forceReleaseJob.run(holdId);
        if (!result.freed()) {
            throw new ApiException(ErrorCodes.SLOT_UNAVAILABLE, "无 LOCKED 占用可释放");
        }
        return ApiResponse.ok(result);
    }

    void authorize(HttpServletRequest request, String token) {
        AppProperties.Internal.ForceRelease cfg = properties.getInternal().getForceRelease();
        if (!cfg.isEnabled()) {
            throw new ApiException(ErrorCodes.FORBIDDEN, "内部强制释放未开启");
        }
        String expected = cfg.getToken();
        if (expected == null || expected.isBlank()) {
            throw new ApiException(ErrorCodes.FORBIDDEN, "内部强制释放未配置");
        }
        if (!isLoopback(request)) {
            throw new ApiException(ErrorCodes.FORBIDDEN, "仅本机可调用");
        }
        if (!tokenMatches(expected, token)) {
            throw new ApiException(ErrorCodes.UNAUTHORIZED, "内部令牌无效");
        }
    }

    static boolean isLoopback(HttpServletRequest request) {
        String addr = request == null ? null : request.getRemoteAddr();
        if (addr == null || addr.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(addr).isLoopbackAddress();
        } catch (Exception ex) {
            return false;
        }
    }

    static boolean tokenMatches(String expected, String provided) {
        if (expected == null || provided == null) {
            return false;
        }
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = provided.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}
