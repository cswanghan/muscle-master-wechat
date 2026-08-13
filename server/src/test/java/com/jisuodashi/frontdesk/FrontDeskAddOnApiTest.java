package com.jisuodashi.frontdesk;

import com.jisuodashi.auth.CustomerRepository;
import com.jisuodashi.auth.DemoStaffIds;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.JwtService;
import com.jisuodashi.auth.TokenType;
import com.jisuodashi.catalog.DemoCatalogIds;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import com.jisuodashi.order.FireContext;
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
class FrontDeskAddOnApiTest {

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
    void cashAddOnWithFrontJwt() {
        long orderId = inServiceWalkIn("ao-cash", "18600007777", 64);
        Map<String, Object> added = data(post(
                "/api/v1/f/orders/" + orderId + "/add-on",
                addOn("ao-cash-1", 30, "CASH"),
                frontToken()), HttpStatus.OK);
        assertThat(added.get("payChannel")).isEqualTo("CASH");
        assertThat(added.get("status")).isEqualTo("IN_SERVICE");
        assertThat(((Number) added.get("amountFen")).longValue()).isEqualTo(9800L);
        assertThat(((Number) added.get("endSlotNo")).intValue()).isEqualTo(71);
        BookingOrderRef after = occupyStore.findOrderById(orderId);
        assertThat(after.endSlotNo()).isEqualTo(71);
        assertThat(after.addOnHoldId()).isNull();
        assertThat(after.payableFen()).isEqualTo(19800 + 9800);
        assertThat(occupyStore.therapistSlot(
                DemoCatalogIds.THERAPIST_LIN, after.serviceDate(), 68).status)
                .isEqualTo("BOOKED");
        assertThat(occupyStore.therapistSlot(
                DemoCatalogIds.THERAPIST_LIN, after.serviceDate(), 70).status)
                .isEqualTo("BUFFER");
    }

    @Test
    void wechatAddOnQrThenNotify() {
        long orderId = inServiceWalkIn("ao-wx", "18600008888", 48);
        Map<String, Object> added = data(post(
                "/api/v1/f/orders/" + orderId + "/add-on",
                addOn("ao-wx-1", 30, "WECHAT"),
                frontToken()), HttpStatus.OK);
        assertThat(added.get("payChannel")).isEqualTo("WECHAT");
        assertThat(added.get("codeUrl").toString()).startsWith("weixin://wxpay/bizpayurl?pr=LIVE_");
        String paymentNo = String.valueOf(added.get("paymentNo"));
        assertThat(((Number) added.get("endSlotNo")).intValue()).isEqualTo(53);
        assertThat(occupyStore.findOrderById(orderId).addOnHoldId()).isNotNull();

        Map<String, Object> pending = data(get("/api/v1/f/payments/" + paymentNo, frontToken()), HttpStatus.OK);
        assertThat(pending.get("status")).isEqualTo("PENDING");

        ResponseEntity<Map<String, Object>> notify = rest.exchange(
                "/api/v1/pay/wechat/notify",
                HttpMethod.POST,
                new HttpEntity<>(notifyBody(paymentNo, 9800), jsonHeaders(null)),
                MAP);
        assertThat(notify.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> paid = data(get("/api/v1/f/payments/" + paymentNo, frontToken()), HttpStatus.OK);
        assertThat(paid.get("status")).isEqualTo("SUCCESS");
        BookingOrderRef after = occupyStore.findOrderById(orderId);
        assertThat(after.status()).isEqualTo("IN_SERVICE");
        assertThat(after.endSlotNo()).isEqualTo(55);
        assertThat(after.addOnHoldId()).isNull();
    }

    @Test
    void wechatAddOnReplayReturnsQr() {
        long orderId = inServiceWalkIn("ao-replay", "18600007070", 40);
        Map<String, Object> first = data(post(
                "/api/v1/f/orders/" + orderId + "/add-on",
                addOn("ao-replay-1", 30, "WECHAT"),
                frontToken()), HttpStatus.OK);
        assertThat(first.get("codeUrl").toString()).startsWith("weixin://wxpay/bizpayurl?pr=LIVE_");
        Map<String, Object> replay = data(post(
                "/api/v1/f/orders/" + orderId + "/add-on",
                addOn("ao-replay-1", 30, "WECHAT"),
                frontToken()), HttpStatus.OK);
        assertThat(replay.get("replay")).isEqualTo(true);
        assertThat(replay.get("paymentNo")).isEqualTo(first.get("paymentNo"));
        assertThat(replay.get("codeUrl")).isEqualTo(first.get("codeUrl"));
        assertThat(occupyStore.findOrderById(orderId).addOnHoldId()).isNotNull();
    }

    @Test
    void supersededAddOnQrDoesNotConfirmHold() {
        long orderId = inServiceWalkIn("ao-stale", "18600008080", 44);
        Map<String, Object> first = data(post(
                "/api/v1/f/orders/" + orderId + "/add-on",
                addOn("ao-stale-1", 30, "WECHAT"),
                frontToken()), HttpStatus.OK);
        String oldNo = String.valueOf(first.get("paymentNo"));
        payments.expirePrepay(oldNo, java.time.LocalDateTime.of(2020, 1, 1, 0, 0));
        Map<String, Object> next = data(post(
                "/api/v1/f/orders/" + orderId + "/add-on",
                addOn("ao-stale-1", 30, "WECHAT"),
                frontToken()), HttpStatus.OK);
        assertThat(next.get("paymentNo")).isNotEqualTo(oldNo);
        assertThat(next.get("codeUrl")).isNotNull();

        ResponseEntity<Map<String, Object>> notify = rest.exchange(
                "/api/v1/pay/wechat/notify",
                HttpMethod.POST,
                new HttpEntity<>(notifyBody(oldNo, 9800), jsonHeaders(null)),
                MAP);
        assertThat(notify.getStatusCode()).isEqualTo(HttpStatus.OK);
        BookingOrderRef after = occupyStore.findOrderById(orderId);
        assertThat(after.addOnHoldId()).isNotNull();
        assertThat(after.endSlotNo()).isEqualTo(49);
        assertThat(payments.findByPaymentNo(oldNo).success()).isTrue();
        assertThat(payments.listRefundsByOrderId(orderId)).isNotEmpty();
    }

    @Test
    void addOnRequiresFrontJwt() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/f/orders/1/add-on")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"x\",\"projectId\":\"1\",\"durationMinutes\":15,\"payChannel\":\"CASH\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    void therapistCannotAddOn() {
        long orderId = inServiceWalkIn("ao-t", "18600009999", 56);
        String therapist = jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.T1, TokenType.T, "SELF", List.of(DemoCatalogIds.STORE))).token();
        ResponseEntity<Map<String, Object>> res = post(
                "/api/v1/f/orders/" + orderId + "/add-on",
                addOn("ao-t-1", 15, "CASH"),
                therapist);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(40301);
    }

    private long inServiceWalkIn(String requestId, String phone, int start) {
        Map<String, Object> created = data(post("/api/v1/f/walk-ins", walkIn(
                requestId, phone, start), frontToken()), HttpStatus.CREATED);
        long orderId = Long.parseLong(String.valueOf(created.get("orderId")));
        machine.fire(orderId, OrderEvent.START_SERVICE, FireContext.system());
        assertThat(occupyStore.findOrderById(orderId).status()).isEqualTo("IN_SERVICE");
        return orderId;
    }

    private String frontToken() {
        return jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.FRONT, TokenType.F, "STORE", List.of(DemoCatalogIds.STORE))).token();
    }

    private static Map<String, Object> addOn(String requestId, int minutes, String channel) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", requestId);
        m.put("projectId", String.valueOf(DemoCatalogIds.PROJECT_P60));
        m.put("durationMinutes", minutes);
        m.put("payChannel", channel);
        return m;
    }

    private static Map<String, Object> walkIn(String requestId, String phone, int startSlotNo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", requestId);
        m.put("phone", phone);
        m.put("customerName", "加钟客");
        m.put("therapistId", String.valueOf(DemoCatalogIds.THERAPIST_LIN));
        m.put("projectId", String.valueOf(DemoCatalogIds.PROJECT_P60));
        m.put("date", "2026-08-14");
        m.put("startSlotNo", startSlotNo);
        m.put("alreadyInStore", true);
        m.put("payChannel", "CASH");
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
}
