package com.jisuodashi.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.ErrorCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private Clock clock;
    private JwtService jwt;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-08-14T04:00:00Z"), ZoneOffset.UTC);
        AppProperties props = new AppProperties();
        props.getJwt().setSecret("dev-only-jwt-hs256-secret-key-32b");
        props.getJwt().setCustomerTtl(Duration.ofHours(2));
        props.getJwt().setStaffTtl(Duration.ofHours(8));
        jwt = new JwtService(props, clock, new ObjectMapper());
    }

    @Test
    void customerTokenHasCidAndTwoHourTtl() {
        JwtService.IssuedToken issued = jwt.issue(JwtPrincipal.customer(1001L));
        assertThat(issued.expiresIn()).isEqualTo(7200);
        JwtPrincipal parsed = jwt.parse(issued.token());
        assertThat(parsed.typ()).isEqualTo(TokenType.C);
        assertThat(parsed.subjectId()).isEqualTo(1001L);
        assertThat(parsed.customerId()).isEqualTo(1001L);
        assertThat(issued.token().split("\\.")).hasSize(3);
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
}
