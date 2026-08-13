package com.jisuodashi.rbac;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jisuodashi.common.ApiResponse;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.ErrorCodes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Hook on POST /c/bookings. P0 default off — bookings can land later. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class CaptchaFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Captcha-Token";
    public static final String PATH = "/api/v1/c/bookings";

    private final AppProperties properties;
    private final ObjectMapper mapper;

    public CaptchaFilter(AppProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (matches(request) && properties.getBooking().getCaptcha().isEnabled()) {
            String token = request.getHeader(HEADER);
            if (token == null || token.isBlank()) {
                writeError(response);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    static boolean matches(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getServletPath();
        return PATH.equals(path);
    }

    private void writeError(HttpServletResponse response) throws IOException {
        response.setStatus(ErrorCodes.httpStatus(ErrorCodes.BAD_REQUEST).value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ApiResponse.error(ErrorCodes.BAD_REQUEST, "缺少验证码"));
    }
}
