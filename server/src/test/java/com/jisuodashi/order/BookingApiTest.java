package com.jisuodashi.order;

import com.jisuodashi.auth.DemoStaffIds;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.JwtService;
import com.jisuodashi.auth.TokenType;
import com.jisuodashi.catalog.DemoCatalogIds;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class BookingApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JwtService jwt;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemorySlotOccupyStore occupyStore;

    @BeforeEach
    void resetCalendar() {
        occupyStore.resetDemoCalendar();
    }

    @Test
    void customerJwtCreatesPendingPayOrder() {
        String token = customerToken();
        ResponseEntity<Map<String, Object>> res = post(body("req-book-1", 78), token);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("code")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertThat(data.get("status")).isEqualTo("PENDING_PAY");
        assertThat(data.get("orderId")).isNotNull();
        assertThat(data.get("orderNo").toString()).startsWith("JS20260814");
        assertThat(data.get("payableFen")).isEqualTo(19800);
        assertThat(data.get("lockExpireAt")).isNotNull();
    }

    @Test
    void sameRequestIdIsIdempotent() {
        String token = customerToken();
        Map<String, Object> first = data(post(body("req-idem-c", 60), token));
        Map<String, Object> replay = data(post(body("req-idem-c", 60), token));
        assertThat(replay.get("orderId")).isEqualTo(first.get("orderId"));
        assertThat(replay.get("orderNo")).isEqualTo(first.get("orderNo"));
        assertThat(replay.get("status")).isEqualTo("PENDING_PAY");
        assertThat(occupyStore.occupancyCount()).isEqualTo(10);
    }

    @Test
    void missingJwtIs40101() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/c/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-no-jwt","storeId":"1","therapistId":"1","projectId":"1","date":"2026-08-14","startSlotNo":64}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    void staffJwtIs40301() {
        String staff = jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.MANAGER, TokenType.F, "STORE", List.of(DemoCatalogIds.STORE))).token();
        ResponseEntity<Map<String, Object>> res = post(body("req-staff", 68), staff);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(40301);
    }

    private String customerToken() {
        return jwt.issue(JwtPrincipal.customer(8_100_000_000_000_000_001L)).token();
    }

    private static Map<String, Object> body(String requestId, int startSlotNo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", requestId);
        m.put("storeId", String.valueOf(DemoCatalogIds.STORE));
        m.put("therapistId", String.valueOf(DemoCatalogIds.THERAPIST_LIN));
        m.put("projectId", String.valueOf(DemoCatalogIds.PROJECT_P60));
        m.put("date", "2026-08-14");
        m.put("startSlotNo", startSlotNo);
        return m;
    }

    private ResponseEntity<Map<String, Object>> post(Map<String, Object> body, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return rest.exchange("/api/v1/c/bookings", HttpMethod.POST, new HttpEntity<>(body, headers), MAP);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> res) {
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).isNotNull();
        return (Map<String, Object>) res.getBody().get("data");
    }
}
