package com.jisuodashi.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.ErrorCodes;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class JwtService {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final byte[] secret;
    private final Duration customerTtl;
    private final Duration staffTtl;
    private final Clock clock;
    private final ObjectMapper mapper;

    public JwtService(AppProperties properties, Clock clock, ObjectMapper mapper) {
        this.secret = properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        this.customerTtl = properties.getJwt().getCustomerTtl();
        this.staffTtl = properties.getJwt().getStaffTtl();
        this.clock = clock;
        this.mapper = mapper;
    }

    public Duration ttlFor(TokenType typ) {
        return typ == TokenType.C ? customerTtl : staffTtl;
    }

    public IssuedToken issue(JwtPrincipal principal) {
        return issue(principal, ttlFor(principal.typ()));
    }

    public IssuedToken issue(JwtPrincipal principal, Duration ttl) {
        Instant now = clock.instant();
        Instant exp = now.plus(ttl);
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", String.valueOf(principal.subjectId()));
        claims.put("typ", principal.typ().name());
        if (principal.typ() == TokenType.C) {
            claims.put("cid", String.valueOf(principal.customerId()));
        } else {
            claims.put("sid", String.valueOf(principal.staffId()));
            if (principal.scopeType() != null) {
                claims.put("scope", principal.scopeType());
            }
            List<String> stores = new ArrayList<>();
            for (Long id : principal.storeIds()) {
                stores.add(String.valueOf(id));
            }
            claims.put("stores", stores);
        }
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", exp.getEpochSecond());
        String token = compact(claims);
        return new IssuedToken(token, (int) ttl.toSeconds(), exp);
    }

    public JwtPrincipal parse(String token) {
        if (token == null || token.isBlank()) {
            throw new ApiException(ErrorCodes.UNAUTHORIZED, "未登录");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new ApiException(ErrorCodes.UNAUTHORIZED, "未登录");
        }
        byte[] expected = hmac((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        byte[] actual;
        try {
            actual = B64D.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCodes.UNAUTHORIZED, "未登录");
        }
        if (!constantTimeEquals(expected, actual)) {
            throw new ApiException(ErrorCodes.UNAUTHORIZED, "未登录");
        }
        Map<String, Object> claims;
        try {
            claims = mapper.readValue(B64D.decode(parts[1]), MAP);
        } catch (Exception e) {
            throw new ApiException(ErrorCodes.UNAUTHORIZED, "未登录");
        }
        long exp = asLong(claims.get("exp"));
        if (exp > 0 && clock.instant().getEpochSecond() >= exp) {
            throw new ApiException(ErrorCodes.TOKEN_EXPIRED, "Token 过期");
        }
        TokenType typ;
        try {
            typ = TokenType.valueOf(String.valueOf(claims.get("typ")));
        } catch (Exception e) {
            throw new ApiException(ErrorCodes.UNAUTHORIZED, "未登录");
        }
        long sub = asLong(claims.get("sub"));
        if (typ == TokenType.C) {
            return JwtPrincipal.customer(sub);
        }
        List<Long> stores = new ArrayList<>();
        Object rawStores = claims.get("stores");
        if (rawStores instanceof List<?> list) {
            for (Object item : list) {
                stores.add(asLong(item));
            }
        }
        String scope = claims.get("scope") == null ? null : String.valueOf(claims.get("scope"));
        return JwtPrincipal.staff(sub, typ, scope, stores);
    }

    private String compact(Map<String, Object> claims) {
        try {
            String header = B64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String payload = B64.encodeToString(mapper.writeValueAsBytes(claims));
            String sig = B64.encodeToString(hmac((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
            return header + "." + payload + "." + sig;
        } catch (Exception e) {
            throw new IllegalStateException("jwt encode failed", e);
        }
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int r = 0;
        for (int i = 0; i < a.length; i++) {
            r |= a[i] ^ b[i];
        }
        return r == 0;
    }

    private static long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value == null) {
            throw new ApiException(ErrorCodes.UNAUTHORIZED, "未登录");
        }
        return Long.parseLong(String.valueOf(value));
    }

    public record IssuedToken(String token, int expiresIn, Instant expireAt) {
    }
}
