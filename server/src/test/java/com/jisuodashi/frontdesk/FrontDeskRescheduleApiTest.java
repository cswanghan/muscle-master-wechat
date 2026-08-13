package com.jisuodashi.frontdesk;

import com.jisuodashi.auth.CustomerRepository;
import com.jisuodashi.auth.DemoStaffIds;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.JwtService;
import com.jisuodashi.auth.TokenType;
import com.jisuodashi.catalog.DemoCatalogIds;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.order.OrderEvent;
import com.jisuodashi.order.OrderStateMachine;
import com.jisuodashi.payment.InMemoryPaymentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class FrontDeskRescheduleApiTest {

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

    @Autowired
    private InMemoryPaymentStore payments;

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private OrderStateMachine machine;

    @BeforeEach
    void reset() {
        occupyStore.resetDemoCalendar();
        payments.clear();
        customers.clear();
    }

    @Test
    void frontDeskRescheduleMovesBookedOrder() {
        String customer = customerToken();
        Map<String, Object> created = data(post("/api/v1/c/bookings", booking("rs-api-1", 64), customer),
                HttpStatus.CREATED);
        long orderId = Long.parseLong(String.valueOf(created.get("orderId")));
        machine.fire(orderId, OrderEvent.PAY_SUCCESS);

        Map<String, Object> moved = data(post(
                "/api/v1/f/orders/" + orderId + "/reschedule",
                reschedule("rs-api-1a", "2026-08-14", 72, DemoCatalogIds.THERAPIST_LIN),
                frontToken()), HttpStatus.OK);
        assertThat(moved.get("status")).isEqualTo("BOOKED");
        assertThat(moved.get("startSlotNo")).isEqualTo(72);
        assertThat(String.valueOf(moved.get("therapistId")))
                .isEqualTo(String.valueOf(DemoCatalogIds.THERAPIST_LIN));
        assertThat(occupyStore.findOrderById(orderId).startSlotNo()).isEqualTo(72);
        assertThat(occupyStore.listChangeLogs()).hasSize(1);
        assertThat(occupyStore.listChangeLogs().getFirst().changeType()).isEqualTo("RESCHEDULE");

        Map<String, Object> replay = data(post(
                "/api/v1/f/orders/" + orderId + "/reschedule",
                reschedule("rs-api-1a", "2026-08-14", 72, DemoCatalogIds.THERAPIST_LIN),
                frontToken()), HttpStatus.OK);
        assertThat(replay.get("replay")).isEqualTo(true);
        assertThat(replay.get("startSlotNo")).isEqualTo(72);
    }

    @Test
    void rescheduleChangeTherapistPrefersOriginalBed() {
        String customer = customerToken();
        Map<String, Object> created = data(post("/api/v1/c/bookings", booking("rs-api-t", 64), customer),
                HttpStatus.CREATED);
        long orderId = Long.parseLong(String.valueOf(created.get("orderId")));
        machine.fire(orderId, OrderEvent.PAY_SUCCESS);
        long bedId = occupyStore.findOrderById(orderId).bedId();

        Map<String, Object> moved = data(post(
                "/api/v1/f/orders/" + orderId + "/reschedule",
                reschedule("rs-api-t1", "2026-08-14", 64, DemoCatalogIds.THERAPIST_CHEN),
                frontToken()), HttpStatus.OK);
        assertThat(moved.get("status")).isEqualTo("BOOKED");
        assertThat(String.valueOf(moved.get("therapistId")))
                .isEqualTo(String.valueOf(DemoCatalogIds.THERAPIST_CHEN));
        assertThat(String.valueOf(moved.get("bedId"))).isEqualTo(String.valueOf(bedId));
        assertThat(occupyStore.findOrderById(orderId).therapistId()).isEqualTo(DemoCatalogIds.THERAPIST_CHEN);
    }

    @Test
    void targetBusyIs40901AndLeavesOccupancy() {
        String customer = customerToken();
        Map<String, Object> first = data(post("/api/v1/c/bookings", booking("rs-api-busy-a", 64), customer),
                HttpStatus.CREATED);
        long stay = Long.parseLong(String.valueOf(first.get("orderId")));
        machine.fire(stay, OrderEvent.PAY_SUCCESS);

        Map<String, Object> body = booking("rs-api-busy-b", 72);
        body.put("therapistId", String.valueOf(DemoCatalogIds.THERAPIST_CHEN));
        Map<String, Object> second = data(post("/api/v1/c/bookings", body, customer), HttpStatus.CREATED);
        long move = Long.parseLong(String.valueOf(second.get("orderId")));
        machine.fire(move, OrderEvent.PAY_SUCCESS);
        int occ = occupyStore.occupancyCount();
        int start = occupyStore.findOrderById(move).startSlotNo();

        ResponseEntity<Map<String, Object>> res = post(
                "/api/v1/f/orders/" + move + "/reschedule",
                reschedule("rs-api-busy-1", "2026-08-14", 64, DemoCatalogIds.THERAPIST_LIN),
                frontToken());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(40901);
        assertThat(occupyStore.occupancyCount()).isEqualTo(occ);
        assertThat(occupyStore.findOrderById(move).startSlotNo()).isEqualTo(start);
        assertThat(occupyStore.findOrderById(move).therapistId()).isEqualTo(DemoCatalogIds.THERAPIST_CHEN);
    }

    @Test
    void statusNotBookedIs40904() {
        String customer = customerToken();
        Map<String, Object> created = data(post("/api/v1/c/bookings", booking("rs-api-ci", 52), customer),
                HttpStatus.CREATED);
        long orderId = Long.parseLong(String.valueOf(created.get("orderId")));
        machine.fire(orderId, OrderEvent.PAY_SUCCESS);
        data(post("/api/v1/f/orders/" + orderId + "/check-in",
                Map.of("requestId", "rs-ci", "verify", "ORDER_NO", "keyword", created.get("orderNo")),
                frontToken()), HttpStatus.OK);

        ResponseEntity<Map<String, Object>> res = post(
                "/api/v1/f/orders/" + orderId + "/reschedule",
                reschedule("rs-api-ci-1", "2026-08-14", 60, DemoCatalogIds.THERAPIST_LIN),
                frontToken());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(40904);
        assertThat(occupyStore.findOrderById(orderId).status()).isEqualTo("CHECKED_IN");
        assertThat(occupyStore.findOrderById(orderId).startSlotNo()).isEqualTo(52);
    }

    @Test
    void rescheduleRequiresFrontDeskJwt() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/f/orders/1/reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"x\",\"date\":\"2026-08-14\",\"startSlotNo\":64,\"therapistId\":\"1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    void therapistCannotReschedule() {
        String customer = customerToken();
        Map<String, Object> created = data(post("/api/v1/c/bookings", booking("rs-api-th", 48), customer),
                HttpStatus.CREATED);
        long orderId = Long.parseLong(String.valueOf(created.get("orderId")));
        machine.fire(orderId, OrderEvent.PAY_SUCCESS);
        String therapist = jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.T1, TokenType.T, "SELF", List.of(DemoCatalogIds.STORE))).token();
        ResponseEntity<Map<String, Object>> res = post(
                "/api/v1/f/orders/" + orderId + "/reschedule",
                reschedule("rs-api-th-1", "2026-08-14", 56, DemoCatalogIds.THERAPIST_LIN),
                therapist);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(40301);
    }

    @Test
    void noCustomerRescheduleEndpoint() {
        String customer = customerToken();
        Map<String, Object> created = data(post("/api/v1/c/bookings", booking("rs-api-c", 44), customer),
                HttpStatus.CREATED);
        long orderId = Long.parseLong(String.valueOf(created.get("orderId")));
        machine.fire(orderId, OrderEvent.PAY_SUCCESS);

        ResponseEntity<Map<String, Object>> viaBookings = post(
                "/api/v1/c/bookings/" + orderId + "/reschedule",
                reschedule("c-rs", "2026-08-14", 56, DemoCatalogIds.THERAPIST_LIN),
                customer);
        ResponseEntity<Map<String, Object>> viaOrders = post(
                "/api/v1/c/orders/" + orderId + "/reschedule",
                reschedule("c-rs2", "2026-08-14", 56, DemoCatalogIds.THERAPIST_LIN),
                customer);
        assertThat(viaBookings.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(viaOrders.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(viaBookings.getStatusCode().value()).isIn(404, 405, 500);
        assertThat(occupyStore.findOrderById(orderId).startSlotNo()).isEqualTo(44);
        assertThat(occupyStore.listChangeLogs()).isEmpty();
    }

    private String customerToken() {
        return jwt.issue(JwtPrincipal.customer(8_100_000_000_000_000_001L)).token();
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

    private static Map<String, Object> reschedule(String requestId, String date, int start, long therapistId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", requestId);
        m.put("date", date);
        m.put("startSlotNo", start);
        m.put("therapistId", String.valueOf(therapistId));
        return m;
    }

    private ResponseEntity<Map<String, Object>> post(String path, Map<String, ?> body, String bearer) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, jsonHeaders(bearer)), MAP);
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
}
