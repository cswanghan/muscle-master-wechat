package com.jisuodashi.frontdesk;

import com.jisuodashi.auth.DemoStaffIds;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.JwtService;
import com.jisuodashi.auth.TokenType;
import com.jisuodashi.catalog.DemoCatalogIds;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.inventory.SlotStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class UtilizationApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final LocalDate DAY = LocalDate.of(2026, 8, 14);

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JwtService jwt;

    @Autowired
    private InMemorySlotOccupyStore occupyStore;

    @BeforeEach
    void reset() {
        occupyStore.resetDemoCalendar();
    }

    @Test
    void fullDayAndByHourForMixedStatuses() {
        occupyStore.therapistSlot(DemoCatalogIds.THERAPIST_LIN, DAY, 40).status = SlotStatus.BOOKED;
        occupyStore.therapistSlot(DemoCatalogIds.THERAPIST_LIN, DAY, 41).status = SlotStatus.BUFFER;
        occupyStore.therapistSlot(DemoCatalogIds.THERAPIST_LIN, DAY, 42).status = SlotStatus.LOCKED;
        occupyStore.therapistSlot(DemoCatalogIds.THERAPIST_LIN, DAY, 43).status = SlotStatus.REST;

        ResponseEntity<Map<String, Object>> res = get("/api/v1/f/metrics/utilization?date=2026-08-14");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = data(res);
        assertThat(data.get("storeId")).isEqualTo(String.valueOf(DemoCatalogIds.STORE));
        assertThat(data.get("date")).isEqualTo("2026-08-14");
        assertThat(data.get("rateX10000")).isInstanceOf(Number.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byHour = (List<Map<String, Object>>) data.get("byHour");
        assertThat(byHour).isNotEmpty();
        assertThat(byHour).extracting(h -> h.get("hour")).contains(10, 11, 21);
        assertThat(byHour).allSatisfy(h -> assertThat(h).containsKeys("hour", "rateX10000"));
        int occupied = 3;
        int denom = 3 * 48 - 1;
        assertThat(((Number) data.get("rateX10000")).intValue()).isEqualTo(occupied * 10_000 / denom);
    }

    @Test
    void emptyDayReturnsNullRate() {
        Map<String, Object> data = data(get("/api/v1/f/metrics/utilization?date=2020-01-01"));
        assertThat(data.get("rateX10000")).isNull();
        assertThat(data.get("byHour")).isEqualTo(List.of());
    }

    @Test
    void missingJwtIs401() {
        ResponseEntity<Map<String, Object>> res = rest.exchange(
                "/api/v1/f/metrics/utilization?date=2026-08-14", HttpMethod.GET, null, MAP);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<Map<String, Object>> get(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.FRONT, TokenType.F, "STORE", List.of(DemoCatalogIds.STORE))).token());
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), MAP);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> res) {
        Map<String, Object> body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("code")).isEqualTo(0);
        return (Map<String, Object>) body.get("data");
    }
}
