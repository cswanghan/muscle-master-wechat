package com.jisuodashi.rbac;

import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.TokenType;
import com.jisuodashi.common.RequestIds;
import com.jisuodashi.common.SnowflakeIdGenerator;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Aspect
@Component
public class AuditLogAspect {

    private static final Pattern ID_IN_PATH = Pattern.compile("/(\\d+)(?:/|$)");

    private final AuditLogRepository audits;
    private final SnowflakeIdGenerator ids;
    private final Clock clock;

    public AuditLogAspect(AuditLogRepository audits, SnowflakeIdGenerator ids, Clock clock) {
        this.audits = audits;
        this.ids = ids;
        this.clock = clock;
    }

    @AfterReturning(
            "@annotation(com.jisuodashi.rbac.Audited) || "
                    + "((within(com.jisuodashi.admin..*) || within(com.jisuodashi.frontdesk..*)) "
                    + "&& (@annotation(org.springframework.web.bind.annotation.PostMapping) "
                    + "|| @annotation(org.springframework.web.bind.annotation.PutMapping) "
                    + "|| @annotation(org.springframework.web.bind.annotation.PatchMapping) "
                    + "|| @annotation(org.springframework.web.bind.annotation.DeleteMapping)))")
    public void afterSuccess(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Audited audited = signature.getMethod().getAnnotation(Audited.class);
        HttpServletRequest request = currentRequest();
        String method = request == null ? "POST" : request.getMethod();
        if (audited == null && !StoreScopedWriteScanner.WRITE_METHODS.contains(method)) {
            return;
        }
        AuditLogEntry entry = new AuditLogEntry();
        entry.setId(ids.nextId());
        JwtPrincipal principal = AuthContext.get();
        if (principal == null) {
            entry.setActorType("SYSTEM");
        } else if (principal.typ() == TokenType.C) {
            entry.setActorType("CUSTOMER");
            entry.setActorId(principal.subjectId());
        } else {
            entry.setActorType("STAFF");
            entry.setActorId(principal.subjectId());
        }
        if (audited != null) {
            entry.setAction(audited.action());
            entry.setResourceType(audited.resourceType());
        } else {
            entry.setAction(method);
            entry.setResourceType(resourceType(signature));
        }
        entry.setResourceId(firstPathId(request, joinPoint.getArgs()));
        StoreScope scope = StoreScopeContext.get();
        if (scope != null && scope.storeIds().size() == 1) {
            entry.setStoreId(scope.storeIds().getFirst());
        } else if (request != null) {
            entry.setStoreId(storeIdFromRequest(request, joinPoint.getArgs()));
        }
        if (request != null) {
            entry.setIp(request.getRemoteAddr());
            entry.setUserAgent(trim(request.getHeader("User-Agent"), 255));
        }
        entry.setRequestId(RequestIds.current());
        entry.setCreatedAt(Instant.now(clock));
        audits.insert(entry);
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    private static String resourceType(MethodSignature signature) {
        String simple = signature.getDeclaringType().getSimpleName();
        return simple.replace("Controller", "").toUpperCase();
    }

    private static Long firstPathId(HttpServletRequest request, Object[] args) {
        if (request != null) {
            Matcher m = ID_IN_PATH.matcher(request.getRequestURI());
            if (m.find()) {
                return Long.parseLong(m.group(1));
            }
        }
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof Long id) {
                    return id;
                }
            }
        }
        return null;
    }

    private static Long storeIdFromRequest(HttpServletRequest request, Object[] args) {
        String q = request.getParameter("storeId");
        if (q != null && !q.isBlank()) {
            try {
                return Long.parseLong(q);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof RbacDtos.StoreStatusRequest status && status.storeIdHint() != null) {
                    return status.storeIdHint();
                }
                if (arg instanceof RbacDtos.DeskNoteRequest note) {
                    return parseLong(note.storeId());
                }
            }
        }
        return null;
    }

    private static Long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
