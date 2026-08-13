package com.jisuodashi.rbac;

import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * CI gate: /api/v1/f and /api/v1/a handlers must carry {@link StoreScoped};
 * writes must also carry {@link RequirePerm}.
 */
public final class StoreScopedWriteScanner {

    public static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> SKIP_METHODS = Set.of("OPTIONS");

    private StoreScopedWriteScanner() {
    }

    public static List<String> violations(RequestMappingHandlerMapping mapping) {
        List<String> missing = new ArrayList<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            Collection<String> patterns = patterns(info);
            Collection<String> httpMethods = methods(info);
            if (!isFaPath(patterns) || isFixture(patterns) || onlyOptions(httpMethods)) {
                return;
            }
            if (isMissingStoreScoped(handler.getMethod(), handler.getBeanType())) {
                missing.add("StoreScoped " + describe(handler, patterns));
            }
            if (isWrite(httpMethods) && isMissingRequirePerm(handler.getMethod(), handler.getBeanType())) {
                missing.add("RequirePerm " + describe(handler, patterns));
            }
        });
        return missing;
    }

    public static boolean isUnscopedWrite(
            Collection<String> patterns, Collection<String> httpMethods, Method method, Class<?> beanType) {
        if (!isFaPath(patterns) || isFixture(patterns) || !isWrite(httpMethods)) {
            return false;
        }
        return isMissingStoreScoped(method, beanType);
    }

    public static boolean isMissingStoreScoped(Method method, Class<?> beanType) {
        return method.getAnnotation(StoreScoped.class) == null
                && beanType.getAnnotation(StoreScoped.class) == null;
    }

    public static boolean isMissingRequirePerm(Method method, Class<?> beanType) {
        return method.getAnnotation(RequirePerm.class) == null
                && beanType.getAnnotation(RequirePerm.class) == null;
    }

    public static boolean isFaPath(Collection<String> patterns) {
        for (String pattern : patterns) {
            if (isFaPath(pattern)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isFaPath(String path) {
        return prefix(path, "/api/v1/f") || prefix(path, "/api/v1/a");
    }

    public static boolean isAdminPath(String path) {
        return prefix(path, "/api/v1/a");
    }

    public static boolean isTherapistPath(String path) {
        return prefix(path, "/api/v1/t");
    }

    public static boolean isWrite(Collection<String> httpMethods) {
        if (httpMethods == null || httpMethods.isEmpty()) {
            return true;
        }
        for (String method : httpMethods) {
            if (WRITE_METHODS.contains(method.toUpperCase())) {
                return true;
            }
        }
        return false;
    }

    public static boolean isFixture(Collection<String> patterns) {
        for (String pattern : patterns) {
            if (isFixturePath(pattern)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isFixturePath(String path) {
        return prefix(path, "/api/v1/f/_fixture") || prefix(path, "/api/v1/a/_fixture");
    }

    private static boolean onlyOptions(Collection<String> httpMethods) {
        return httpMethods != null
                && !httpMethods.isEmpty()
                && httpMethods.stream().allMatch(m -> SKIP_METHODS.contains(m.toUpperCase()));
    }

    static boolean prefix(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/") || path.startsWith(prefix + "{");
    }

    private static Set<String> patterns(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null
                && !info.getPathPatternsCondition().getPatternValues().isEmpty()) {
            return info.getPathPatternsCondition().getPatternValues();
        }
        return info.getPatternsCondition() == null ? Set.of() : info.getPatternsCondition().getPatterns();
    }

    private static List<String> methods(RequestMappingInfo info) {
        return info.getMethodsCondition().getMethods().stream().map(RequestMethod::name).toList();
    }

    private static String describe(HandlerMethod handler, Collection<String> patterns) {
        return handler.getBeanType().getSimpleName()
                + "#"
                + handler.getMethod().getName()
                + " "
                + patterns;
    }
}
