package com.jisuodashi.e2e;

import com.jisuodashi.auth.CustomerRepository;
import com.jisuodashi.auth.DemoStaffIds;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.JwtService;
import com.jisuodashi.auth.TokenType;
import com.jisuodashi.catalog.DemoCatalogIds;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.inventory.SlotStatus;
import com.jisuodashi.job.SlotScanJob;
import com.jisuodashi.payment.InMemoryPaymentStore;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gray slice = PR7+PR8+PR9+PR11: login, browse, book+pay, check-in,
 * cash walk-in, unpaid cancel / PAY_TIMEOUT ReleaseLock.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class GraySliceE2eTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final LocalDate DAY = LocalDate.of(2026, 8, 14);

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JwtService jwt;

    @Autowired
    private InMemorySlotOccupyStore occupyStore;

    @Autowired
    private InMemoryPaymentStore payments;

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private SlotScanJob scanJob;

    @BeforeEach
    void reset() {
        occupyStore.resetDemoCalendar();
        payments.clear();
        customers.clear();
    }

    @Test
    void graySliceLoginBrowseBookPayCheckInWalkInCancelAndTimeout() {
        Map<String, Object> login = data(post("/api/v1/c/auth/wechat",
                Map.of("code", "mock:gray-slice-c"), null), HttpStatus.OK);
        String customer = String.valueOf(login.get("token"));
        assertThat(customer).isNotBlank();
        assertThat(login.get("expiresIn")).isEqualTo(7200);
        assertThat(login.get("customerId")).isNotNull();

        Map<String, Object> stores = data(get("/api/v1/c/stores", null), HttpStatus.OK);
        List<Map<String, Object>> storeItems = items(stores);
        assertThat(storeItems).hasSize(1);
        assertThat(storeItems.getFirst().get("storeId")).isEqualTo(String.valueOf(DemoCatalogIds.STORE));
        assertThat(storeItems.getFirst().get("name")).isEqualTo("肌松大师·演示旗舰店");

        List<Map<String, Object>> therapists = items(data(get("/api/v1/c/therapists", null), HttpStatus.OK));
        assertThat(therapists).extracting(t -> t.get("name")).contains("林晓", "陈默", "周可");

        List<Map<String, Object>> projects = items(data(
                get("/api/v1/c/projects?storeId=" + DemoCatalogIds.STORE, null), HttpStatus.OK));
        assertThat(projects).extracting(p -> p.get("priceFen")).contains(19800);

        Map<String, Object> booked = data(post("/api/v1/c/bookings", booking("gray-book-1", 44), customer),
                HttpStatus.CREATED);
        assertThat(booked.get("status")).isEqualTo("PENDING_PAY");
        long paidOrderId = Long.parseLong(String.valueOf(booked.get("orderId")));
        assertThat(occupyStore.occupancyCount()).isEqualTo(10);

        Map<String, Object> pay = data(post("/api/v1/c/bookings/" + paidOrderId + "/pay",
                Map.of("requestId", "gray-pay-1"), customer), HttpStatus.OK);
        String paymentNo = String.valueOf(pay.get("paymentNo"));
        assertThat(pay.get("amountFen")).isEqualTo(19800);

        ResponseEntity<Map<String, Object>> notify = rest.exchange(
                "/api/v1/pay/wechat/notify",
                HttpMethod.POST,
                new HttpEntity<>(notifyBody(paymentNo, 19800), jsonHeaders(null)),
                MAP);
        assertThat(notify.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(notify.getBody()).isNotNull();
        assertThat(notify.getBody().get("code")).isEqualTo("SUCCESS");
        assertThat(occupyStore.findOrderById(paidOrderId).status()).isEqualTo("BOOKED");

        Map<String, Object> checked = data(post(
                "/api/v1/f/orders/" + paidOrderId + "/check-in",
                Map.of("requestId", "gray-ci-1", "verify", "ORDER_NO", "keyword", booked.get("orderNo")),
                frontToken()), HttpStatus.OK);
        assertThat(checked.get("status")).isEqualTo("CHECKED_IN");
        assertThat(checked.get("roomName")).isEqualTo("一号房");

        Map<String, Object> walkIn = data(post("/api/v1/f/walk-ins", walkIn(
                "gray-wi-cash", "18600001818", "灰度散客", 56, true, "CASH"), frontToken()), HttpStatus.CREATED);
        assertThat(walkIn.get("status")).isEqualTo("CHECKED_IN");
        assertThat(walkIn.get("payChannel")).isEqualTo("CASH");
        assertThat(walkIn.get("customerMask")).isEqualTo("186****1818");
        assertThat(occupyStore.findOrderById(Long.parseLong(String.valueOf(walkIn.get("orderId")))).status())
                .isEqualTo("CHECKED_IN");

        Map<String, Object> unpaid = data(post("/api/v1/c/bookings", booking("gray-cancel-1", 68), customer),
                HttpStatus.CREATED);
        long cancelId = Long.parseLong(String.valueOf(unpaid.get("orderId")));
        int occAfterUnpaid = occupyStore.occupancyCount();
        Map<String, Object> cancelled = data(post(
                "/api/v1/c/bookings/" + cancelId + "/cancel",
                Map.of("requestId", "gray-cancel-1", "reason", "changed-mind"),
                customer), HttpStatus.OK);
        assertThat(cancelled.get("status")).isEqualTo("CLOSED");
        assertThat(occupyStore.occupancyCount()).isEqualTo(occAfterUnpaid - 10);
        assertThat(occupyStore.therapistSlot(DemoCatalogIds.THERAPIST_LIN, DAY, 68).status)
                .isEqualTo(SlotStatus.FREE);

        Map<String, Object> leftover = data(post("/api/v1/c/bookings", booking("gray-timeout-1", 80), customer),
                HttpStatus.CREATED);
        long leftoverId = Long.parseLong(String.valueOf(leftover.get("orderId")));
        long leftoverHold = occupyStore.findOrderById(leftoverId).holdId();
        assertThat(occupyStore.therapistSlot(DemoCatalogIds.THERAPIST_LIN, DAY, 80).status)
                .isEqualTo(SlotStatus.LOCKED);
        occupyStore.expireHold(leftoverHold, java.time.LocalDateTime.of(2020, 1, 1, 0, 0));
        var scan = scanJob.run();
        assertThat(scan.pendingReleased()).isEqualTo(1);
        assertThat(occupyStore.findOrderById(leftoverId).status()).isEqualTo("CLOSED");
        assertThat(occupyStore.therapistSlot(DemoCatalogIds.THERAPIST_LIN, DAY, 80).status)
                .isEqualTo(SlotStatus.FREE);
        assertThat(occupyStore.findOrderByHoldId(leftoverHold).status()).isEqualTo("CLOSED");
        assertThat(occupyStore.listTherapistDaySlots(DemoCatalogIds.THERAPIST_LIN, DAY).stream()
                .noneMatch(s -> s.slotNo() >= 80 && s.slotNo() <= 84 && !"FREE".equals(s.status())))
                .isTrue();
    }

    private String frontToken() {
        return jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.FRONT, TokenType.F, "STORE", List.of(DemoCatalogIds.STORE))).token();
    }

    private static Map<String, Object> booking(String requestId, int startSlotNo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", requestId);
        m.put("storeId", String.valueOf(DemoCatalogIds.STORE));
        m.put("therapistId", String.valueOf(DemoCatalogIds.THERAPIST_LIN));
        m.put("projectId", String.valueOf(DemoCatalogIds.PROJECT_P60));
        m.put("date", "2026-08-14");
        m.put("startSlotNo", startSlotNo);
        return m;
    }

    private static Map<String, Object> walkIn(
            String requestId, String phone, String name, int startSlotNo, boolean already, String channel) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", requestId);
        m.put("phone", phone);
        m.put("customerName", name);
        m.put("therapistId", String.valueOf(DemoCatalogIds.THERAPIST_LIN));
        m.put("projectId", String.valueOf(DemoCatalogIds.PROJECT_P60));
        m.put("date", "2026-08-14");
        m.put("startSlotNo", startSlotNo);
        m.put("alreadyInStore", already);
        m.put("payChannel", channel);
        return m;
    }

    private static Map<String, Object> notifyBody(String paymentNo, long amountFen) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("out_trade_no", paymentNo);
        m.put("transaction_id", "wx_" + paymentNo);
        m.put("amount_fen", amountFen);
        return m;
    }

    private ResponseEntity<Map<String, Object>> post(String path, Map<String, ?> body, String bearer) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, jsonHeaders(bearer)), MAP);
    }

    private ResponseEntity<Map<String, Object>> get(String path, String bearer) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(jsonHeaders(bearer)), MAP);
    }

    private static HttpHeaders jsonHeaders(String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return headers;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> res, HttpStatus expected) {
        assertThat(res.getStatusCode()).isEqualTo(expected);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(0);
        return (Map<String, Object>) res.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("items");
    }
}
