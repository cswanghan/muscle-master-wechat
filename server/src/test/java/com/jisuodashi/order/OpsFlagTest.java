package com.jisuodashi.order;

import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.JwtService;
import com.jisuodashi.catalog.DemoCatalogIds;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.FeatureFlags;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.inventory.LockNewCommand;
import com.jisuodashi.inventory.OccupyFixtures;
import com.jisuodashi.inventory.SlotOccupyService;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class OpsFlagTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JwtService jwt;

    @Autowired
    private AppProperties properties;

    @Autowired
    private FeatureFlags flags;

    @Autowired
    private SlotOccupyService occupy;

    @Autowired
    private InMemorySlotOccupyStore occupyStore;

    @BeforeEach
    void reset() {
        occupyStore.resetDemoCalendar();
        restoreFlags();
    }

    @AfterEach
    void restore() {
        restoreFlags();
    }

    @Test
    void lockDisabledRejectsLockNewAndBookWith403() {
        properties.getFlags().getBooking().getLock().setEnabled(false);
        flags.refresh();

        assertThatThrownBy(() -> occupy.lockNew(new LockNewCommand(
                "lock-off",
                8_100_000_000_000_000_001L,
                DemoCatalogIds.STORE,
                DemoCatalogIds.THERAPIST_LIN,
                DemoCatalogIds.PROJECT_P60,
                LocalDate.of(2026, 8, 14),
                64,
                LockNewCommand.SOURCE_MINI_C)))
                .hasMessageContaining("锁库存已关闭");

        ResponseEntity<Map<String, Object>> res = postBook("req-lock-off", 68);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(40301);
    }

    @Test
    void bookingDisabledStillAllowsBrowse() {
        properties.getFlags().getBooking().setEnabled(false);
        flags.refresh();

        ResponseEntity<Map<String, Object>> stores = rest.exchange("/api/v1/c/stores", HttpMethod.GET, null, MAP);
        assertThat(stores.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stores.getBody()).isNotNull();
        assertThat(stores.getBody().get("code")).isEqualTo(0);

        ResponseEntity<Map<String, Object>> book = postBook("req-book-off", 72);
        assertThat(book.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(book.getBody()).isNotNull();
        assertThat(book.getBody().get("code")).isEqualTo(40301);
    }

    @Test
    void lockNewWorksWhenFlagsDefaultOn() {
        occupy.lockNew(OccupyFixtures.cmd("lock-on", OccupyFixtures.T1, 76));
        assertThat(occupyStore.occupancyCount()).isGreaterThan(0);
    }

    private ResponseEntity<Map<String, Object>> postBook(String requestId, int start) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("storeId", String.valueOf(DemoCatalogIds.STORE));
        body.put("therapistId", String.valueOf(DemoCatalogIds.THERAPIST_LIN));
        body.put("projectId", String.valueOf(DemoCatalogIds.PROJECT_P60));
        body.put("date", "2026-08-14");
        body.put("startSlotNo", start);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(jwt.issue(JwtPrincipal.customer(8_100_000_000_000_000_001L)).token());
        return rest.exchange("/api/v1/c/bookings", HttpMethod.POST, new HttpEntity<>(body, headers), MAP);
    }

    private void restoreFlags() {
        properties.getFlags().getBooking().setEnabled(true);
        properties.getFlags().getBooking().getLock().setEnabled(true);
        properties.getFlags().getPay().getWechat().setEnabled(true);
        properties.getFlags().getWorkflow().getReschedule().setEnabled(true);
        properties.getFlags().getWorkflow().getAddOn().setEnabled(true);
        properties.getFlags().getWorkflow().getSwap().setEnabled(true);
        properties.getFlags().getWorkflow().getRefund().setEnabled(true);
        flags.refresh();
    }
}
