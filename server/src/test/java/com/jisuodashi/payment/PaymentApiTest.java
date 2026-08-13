package com.jisuodashi.payment;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class PaymentApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JwtService jwt;

    @Autowired
    private InMemorySlotOccupyStore occupyStore;

    @Autowired
    private InMemoryPaymentStore payments;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void reset() {
        occupyStore.resetDemoCalendar();
        payments.clear();
    }

    @Test
    void payReturnsMockJsapiAndReusesUntilExpire() {
        String token = customerToken();
        Map<String, Object> created = data(post("/api/v1/c/bookings", booking("req-pay-a", 78), token), HttpStatus.CREATED);
        String orderId = String.valueOf(created.get("orderId"));
        assertThat(created.get("payParams")).isInstanceOf(Map.class);

        Map<String, Object> first = data(post("/api/v1/c/bookings/" + orderId + "/pay",
                Map.of("requestId", "pay-1"), token), HttpStatus.OK);
        assertThat(first.get("paymentNo")).isNotNull();
        assertThat(first.get("amountFen")).isEqualTo(19800);
        @SuppressWarnings("unchecked")
        Map<String, String> params = (Map<String, String>) first.get("payParams");
        assertThat(params).containsKeys("timeStamp", "nonceStr", "package", "signType", "paySign");
        assertThat(params.get("package")).startsWith("prepay_id=mock_prepay_");
        assertThat(params.get("signType")).isEqualTo("RSA");

        Map<String, Object> again = data(post("/api/v1/c/bookings/" + orderId + "/pay",
                Map.of("requestId", "pay-2"), token), HttpStatus.OK);
        assertThat(again.get("paymentNo")).isEqualTo(first.get("paymentNo"));
        assertThat(again.get("reused")).isEqualTo(true);
    }

    @Test
    void notifyThenFrontPollAndLateReleaseLock() {
        String token = customerToken();
        Map<String, Object> created = data(post("/api/v1/c/bookings", booking("req-pay-b", 60), token), HttpStatus.CREATED);
        String orderId = String.valueOf(created.get("orderId"));
        Map<String, Object> pay = data(post("/api/v1/c/bookings/" + orderId + "/pay",
                Map.of("requestId", "pay-b"), token), HttpStatus.OK);
        String paymentNo = String.valueOf(pay.get("paymentNo"));

        ResponseEntity<Map<String, Object>> notify = rest.exchange(
                "/api/v1/pay/wechat/notify",
                HttpMethod.POST,
                new HttpEntity<>(notifyBody(paymentNo, 19800), jsonHeaders(null)),
                MAP);
        assertThat(notify.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(notify.getBody()).isNotNull();
        assertThat(notify.getBody().get("code")).isEqualTo("SUCCESS");

        ResponseEntity<Map<String, Object>> replay = rest.exchange(
                "/api/v1/pay/wechat/notify",
                HttpMethod.POST,
                new HttpEntity<>(notifyBody(paymentNo, 19800), jsonHeaders(null)),
                MAP);
        assertThat(replay.getBody()).isNotNull();
        assertThat(replay.getBody().get("code")).isEqualTo("SUCCESS");

        Map<String, Object> view = data(
                get("/api/v1/f/payments/" + paymentNo, managerToken()), HttpStatus.OK);
        assertThat(view.get("paymentNo")).isEqualTo(paymentNo);
        assertThat(view.get("status")).isEqualTo("SUCCESS");
        assertThat(view.get("amountFen")).isEqualTo(19800);
        assertThat(String.valueOf(view.get("orderId"))).isEqualTo(orderId);

        Payment row = payments.findByPaymentNo(paymentNo);
        assertThat(row.success()).isTrue();
        assertThat(occupyStore.findOrderById(Long.parseLong(orderId)).status()).isEqualTo("BOOKED");
        assertThat(occupyStore.jobByHold(occupyStore.findOrderById(Long.parseLong(orderId)).holdId()).status)
                .isEqualTo("DONE");
    }

    @Test
    void payRequiresCustomerJwt() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/c/bookings/1/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    private String customerToken() {
        return jwt.issue(JwtPrincipal.customer(8_100_000_000_000_000_001L)).token();
    }

    private String managerToken() {
        return jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.MANAGER, TokenType.F, "STORE", List.of(DemoCatalogIds.STORE))).token();
    }

    private static Map<String, Object> notifyBody(String paymentNo, long amountFen) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("out_trade_no", paymentNo);
        m.put("transaction_id", "wx_" + paymentNo);
        m.put("amount_fen", amountFen);
        return m;
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
}
