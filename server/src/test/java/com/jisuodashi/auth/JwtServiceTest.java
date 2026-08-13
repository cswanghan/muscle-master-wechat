package com.jisuodashi.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.ErrorCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private Clock clock;
    private JwtService jwt;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-08-14T04:00:00Z"), ZoneOffset.UTC);
        AppProperties props = new AppProperties();
        props.getJwt().setSecret("dev-only-jwt-hs256-secret-key-32b");
        props.getJwt().setCustomerTtl(Duration.ofHours(2));
        props.getJwt().setStaffTtl(Duration.ofHours(8));
        mapper = new ObjectMapper();
        jwt = new JwtService(props, clock, mapper);
    }

    @Test
    void customerTokenHasCidAndTwoHourTtl() {
        JwtService.IssuedToken issued = jwt.issue(JwtPrincipal.customer(1001L));
        assertThat(issued.expiresIn()).isEqualTo(7200);
        JwtPrincipal parsed = jwt.parse(issued.token());
        assertThat(parsed.typ()).isEqualTo(TokenType.C);
        assertThat(parsed.subjectId()).isEqualTo(1001L);
        assertThat(parsed.customerId()).isEqualTo(1001L);
        Map<String, Object> claims = payload(issued.token());
        assertThat(claims.get("sub")).isEqualTo("1001");
        assertThat(claims.get("typ")).isEqualTo("C");
        assertThat(claims.get("cid")).isEqualTo("1001");
        assertThat(claims).doesNotContainKey("sid");
    }

    @Test
    void staffTokenHasSidScopeAndEightHourTtl() {
        JwtPrincipal staff = JwtPrincipal.staff(301L, TokenType.A, "ALL", List.of());
        JwtService.IssuedToken issued = jwt.issue(staff);
        assertThat(issued.expiresIn()).isEqualTo(28800);
        JwtPrincipal parsed = jwt.parse(issued.token());
        assertThat(parsed.typ()).isEqualTo(TokenType.A);
        assertThat(parsed.staffId()).isEqualTo(301L);
        assertThat(parsed.scopeType()).isEqualTo("ALL");
        Map<String, Object> claims = payload(issued.token());
        assertThat(claims.get("sub")).isEqualTo("301");
        assertThat(claims.get("typ")).isEqualTo("A");
        assertThat(claims.get("sid")).isEqualTo("301");
        assertThat(claims.get("scope")).isEqualTo("ALL");
        assertThat(claims.get("stores")).isEqualTo(List.of());
    }

    @Test
    void therapistAndFrontdeskTokensAreEightHours() {
        JwtService.IssuedToken t = jwt.issue(
                JwtPrincipal.staff(304L, TokenType.T, "SELF", List.of(3_100_000_000_000_000_001L)));
        JwtService.IssuedToken f = jwt.issue(
                JwtPrincipal.staff(303L, TokenType.F, "STORE", List.of(3_100_000_000_000_000_001L)));
        assertThat(t.expiresIn()).isEqualTo(28800);
        assertThat(f.expiresIn()).isEqualTo(28800);
        Map<String, Object> tClaims = payload(t.token());
        Map<String, Object> fClaims = payload(f.token());
        assertThat(tClaims.get("typ")).isEqualTo("T");
        assertThat(tClaims.get("sid")).isEqualTo("304");
        assertThat(tClaims.get("scope")).isEqualTo("SELF");
        assertThat(tClaims.get("stores")).isEqualTo(List.of("3100000000000000001"));
        assertThat(fClaims.get("typ")).isEqualTo("F");
        assertThat(fClaims.get("scope")).isEqualTo("STORE");
        assertThat(jwt.parse(t.token()).typ()).isEqualTo(TokenType.T);
        assertThat(jwt.parse(f.token()).typ()).isEqualTo(TokenType.F);
    }

    @Test
    void staffRoleMapsToTokenType() {
        StaffUser therapist = new StaffUser();
        therapist.setRoleCodes(List.of("THERAPIST"));
        assertThat(therapist.tokenType()).isEqualTo(TokenType.T);
        StaffUser front = new StaffUser();
        front.setRoleCodes(List.of("FRONTDESK"));
        assertThat(front.tokenType()).isEqualTo(TokenType.F);
        StaffUser manager = new StaffUser();
        manager.setRoleCodes(List.of("STORE_MANAGER"));
        assertThat(manager.tokenType()).isEqualTo(TokenType.F);
        StaffUser admin = new StaffUser();
        admin.setRoleCodes(List.of("THERAPIST", "SUPER_ADMIN"));
        assertThat(admin.tokenType()).isEqualTo(TokenType.A);
    }

    @Test
    void expiredTokenIs40102() {
        JwtService.IssuedToken issued = jwt.issue(JwtPrincipal.customer(1L), Duration.ofSeconds(-1));
        assertThatThrownBy(() -> jwt.parse(issued.token()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.TOKEN_EXPIRED);
    }

    @Test
    void tamperedTokenIsRejected() {
        JwtService.IssuedToken issued = jwt.issue(JwtPrincipal.customer(1L));
        String tampered = issued.token().substring(0, issued.token().length() - 2) + "xx";
        assertThatThrownBy(() -> jwt.parse(tampered))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.UNAUTHORIZED);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(String token) {
        try {
            String json = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
