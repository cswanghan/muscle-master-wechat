package com.jisuodashi.frontdesk;

import com.jisuodashi.auth.CustomerRepository;
import com.jisuodashi.auth.DemoStaffIds;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.JwtService;
import com.jisuodashi.auth.TokenType;
import com.jisuodashi.catalog.DemoCatalogIds;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.inventory.SlotStatus;
import com.jisuodashi.order.OrderEvent;
import com.jisuodashi.order.OrderStateMachine;
import com.jisuodashi.payment.InMemoryPaymentStore;
import com.jisuodashi.staff.InMemoryTreatmentNoteRepository;
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

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class FrontDeskSwapApiTest {

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
    @Autowired
    private InMemoryTreatmentNoteRepository notes;

    @BeforeEach
    void reset() {
        occupyStore.resetDemoCalendar();
        payments.clear();
        customers.clear();
        notes.clearAdded();
    }

    @Test
    void frontDeskJwtSwapsCheckedInOrder() {
        long orderId = bookedThenCheckedIn("swap-api-ok", 64);
        Map<String, Object> body = swapBody("swap-ok-1", DemoCatalogIds.THERAPIST_CHEN, "指定技师请假");
        Map<String, Object> swapped = data(post("/api/v1/f/orders/" + orderId + "/swap-therapist", body, frontToken()),
                HttpStatus.OK);
        assertThat(swapped.get("status")).isEqualTo("CHECKED_IN");
        assertThat(String.valueOf(swapped.get("oldTherapistId")))
                .isEqualTo(String.valueOf(DemoCatalogIds.THERAPIST_LIN));
        assertThat(String.valueOf(swapped.get("newTherapistId")))
                .isEqualTo(String.valueOf(DemoCatalogIds.THERAPIST_CHEN));
        assertThat(swapped.get("fromSlotNo")).isEqualTo(64);
        assertThat(occupyStore.findOrderById(orderId).therapistId()).isEqualTo(DemoCatalogIds.THERAPIST_CHEN);
        assertThat(occupyStore.therapistSlot(DemoCatalogIds.THERAPIST_CHEN, LocalDate.of(2026, 8, 14), 64).status)
                .isEqualTo(SlotStatus.BOOKED);
        assertThat(occupyStore.therapistSlot(DemoCatalogIds.THERAPIST_LIN, LocalDate.of(2026, 8, 14), 64).status)
                .isEqualTo(SlotStatus.FREE);

        Map<String, Object> replay = data(post(
                "/api/v1/f/orders/" + orderId + "/swap-therapist", body, frontToken()), HttpStatus.OK);
        assertThat(replay.get("replay")).isEqualTo(true);
        assertThat(replay.get("newTherapistId")).isEqualTo(swapped.get("newTherapistId"));
    }

    @Test
    void therapistJwtIs40301() {
        long orderId = bookedThenCheckedIn("swap-api-t", 52);
        String therapist = jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.T1, TokenType.T, "SELF", List.of(DemoCatalogIds.STORE))).token();
        ResponseEntity<Map<String, Object>> res = post(
                "/api/v1/f/orders/" + orderId + "/swap-therapist",
                swapBody("swap-t", DemoCatalogIds.THERAPIST_CHEN, "x"),
                therapist);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(40301);
        assertThat(occupyStore.findOrderById(orderId).therapistId()).isEqualTo(DemoCatalogIds.THERAPIST_LIN);
    }

    @Test
    void noJwtIs40101() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/f/orders/1/swap-therapist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"x\",\"newTherapistId\":\"2\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    void otherStoreIs40302() {
        long orderId = bookedThenCheckedIn("swap-api-scope", 68);
        String otherStore = jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.FRONT, TokenType.F, "STORE", List.of(99L))).token();
        ResponseEntity<Map<String, Object>> res = post(
                "/api/v1/f/orders/" + orderId + "/swap-therapist",
                swapBody("swap-scope", DemoCatalogIds.THERAPIST_CHEN, "x"),
                otherStore);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(40302);
    }

    @Test
    void pendingPayAndBookedReturn40904() {
        String customer = customerToken();
        Map<String, Object> created = data(post("/api/v1/c/bookings", booking("swap-pp", 48), customer),
                HttpStatus.CREATED);
        long orderId = Long.parseLong(String.valueOf(created.get("orderId")));
        ResponseEntity<Map<String, Object>> pending = post(
                "/api/v1/f/orders/" + orderId + "/swap-therapist",
                swapBody("swap-pp-1", DemoCatalogIds.THERAPIST_CHEN, "x"),
                frontToken());
        assertThat(pending.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(pending.getBody()).isNotNull();
        assertThat(pending.getBody().get("code")).isEqualTo(40904);

        machine.fire(orderId, OrderEvent.PAY_SUCCESS);
        ResponseEntity<Map<String, Object>> booked = post(
                "/api/v1/f/orders/" + orderId + "/swap-therapist",
                swapBody("swap-bk-1", DemoCatalogIds.THERAPIST_CHEN, "x"),
                frontToken());
        assertThat(booked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(booked.getBody()).isNotNull();
        assertThat(booked.getBody().get("code")).isEqualTo(40904);
    }

    @Test
    void busyNewTherapistReturns40901() {
        long orderId = bookedThenCheckedIn("swap-api-busy", 60);
        occupyStore.therapistSlot(DemoCatalogIds.THERAPIST_CHEN, LocalDate.of(2026, 8, 14), 61).status =
                SlotStatus.BOOKED;
        ResponseEntity<Map<String, Object>> res = post(
                "/api/v1/f/orders/" + orderId + "/swap-therapist",
                swapBody("swap-busy", DemoCatalogIds.THERAPIST_CHEN, "x"),
                frontToken());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(40901);
        assertThat(occupyStore.findOrderById(orderId).therapistId()).isEqualTo(DemoCatalogIds.THERAPIST_LIN);
        assertThat(occupyStore.therapistSlot(DemoCatalogIds.THERAPIST_LIN, LocalDate.of(2026, 8, 14), 60).status)
                .isEqualTo(SlotStatus.BOOKED);
    }

    private long bookedThenCheckedIn(String requestId, int startSlotNo) {
        String customer = customerToken();
        Map<String, Object> created = data(post("/api/v1/c/bookings", booking(requestId, startSlotNo), customer),
                HttpStatus.CREATED);
        long orderId = Long.parseLong(String.valueOf(created.get("orderId")));
        machine.fire(orderId, OrderEvent.PAY_SUCCESS);
        data(post("/api/v1/f/orders/" + orderId + "/check-in",
                Map.of("requestId", requestId + "-ci", "verify", "ORDER_NO", "keyword", created.get("orderNo")),
                frontToken()), HttpStatus.OK);
        return orderId;
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

    private static Map<String, Object> swapBody(String requestId, long newTherapistId, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", requestId);
        m.put("newTherapistId", String.valueOf(newTherapistId));
        m.put("reason", reason);
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
