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
class FrontDeskApiTest {

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
    void checkInBookedOrder() {
        String customer = customerToken();
        Map<String, Object> created = data(post("/api/v1/c/bookings", booking("desk-ci-1", 64), customer),
                HttpStatus.CREATED);
        long orderId = Long.parseLong(String.valueOf(created.get("orderId")));
        machine.fire(orderId, OrderEvent.PAY_SUCCESS);

        Map<String, Object> checked = data(post(
                "/api/v1/f/orders/" + orderId + "/check-in",
                Map.of("requestId", "ci-1", "verify", "ORDER_NO", "keyword", created.get("orderNo")),
                frontToken()), HttpStatus.OK);
        assertThat(checked.get("status")).isEqualTo("CHECKED_IN");
        assertThat(String.valueOf(checked.get("orderId"))).isEqualTo(String.valueOf(orderId));
        assertThat(checked.get("roomName")).isEqualTo("一号房");
        assertThat(checked.get("bedName")).isIn("1号床", "2号床");

        Map<String, Object> replay = data(post(
                "/api/v1/f/orders/" + orderId + "/check-in",
                Map.of("requestId", "ci-1b"),
                frontToken()), HttpStatus.OK);
        assertThat(replay.get("status")).isEqualTo("CHECKED_IN");
    }

    @Test
    void checkInWrongStoreIs40302() {
        String customer = customerToken();
        Map<String, Object> created = data(post("/api/v1/c/bookings", booking("desk-ci-scope", 68), customer),
                HttpStatus.CREATED);
        long orderId = Long.parseLong(String.valueOf(created.get("orderId")));
        machine.fire(orderId, OrderEvent.PAY_SUCCESS);

        String otherStore = jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.FRONT, TokenType.F, "STORE", List.of(99L))).token();
        ResponseEntity<Map<String, Object>> res = post(
                "/api/v1/f/orders/" + orderId + "/check-in",
                Map.of("requestId", "ci-scope"),
                otherStore);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(40302);
    }

    @Test
    void checkInRequiresStaffJwt() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/f/orders/1/check-in")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    void therapistCannotCheckIn() {
        String customer = customerToken();
        Map<String, Object> created = data(post("/api/v1/c/bookings", booking("desk-ci-t", 52), customer),
                HttpStatus.CREATED);
        long orderId = Long.parseLong(String.valueOf(created.get("orderId")));
        machine.fire(orderId, OrderEvent.PAY_SUCCESS);
        String therapist = jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.T1, TokenType.T, "SELF", List.of(DemoCatalogIds.STORE))).token();
        ResponseEntity<Map<String, Object>> res = post(
                "/api/v1/f/orders/" + orderId + "/check-in",
                Map.of("requestId", "ci-t"),
                therapist);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(40301);
    }

    @Test
    void walkInCashAlreadyInStoreChecksIn() {
        Map<String, Object> created = data(post("/api/v1/f/walk-ins", walkIn(
                "wi-cash-1", "18600001111", "王先生", 72, true, "CASH"), frontToken()), HttpStatus.CREATED);
        assertThat(created.get("status")).isEqualTo("CHECKED_IN");
        assertThat(created.get("payChannel")).isEqualTo("CASH");
        assertThat(created.get("paymentNo").toString()).startsWith("P");
        assertThat(created.get("customerMask")).isEqualTo("186****1111");
        assertThat(created.get("codeUrl")).isNull();
        assertThat(occupyStore.findOrderById(Long.parseLong(String.valueOf(created.get("orderId")))).status())
                .isEqualTo("CHECKED_IN");

        Map<String, Object> replay = data(post("/api/v1/f/walk-ins", walkIn(
                "wi-cash-1", "18600001111", "王先生", 72, true, "CASH"), frontToken()), HttpStatus.OK);
        assertThat(replay.get("orderId")).isEqualTo(created.get("orderId"));
        assertThat(replay.get("replay")).isEqualTo(true);
        assertThat(replay.get("status")).isEqualTo("CHECKED_IN");
    }

    @Test
    void walkInWechatReturnsNativeQrAndPoll() {
        Map<String, Object> created = data(post("/api/v1/f/walk-ins", walkIn(
                "wi-wx-1", "18600002222", "李女士", 76, true, "WECHAT"), frontToken()), HttpStatus.CREATED);
        assertThat(created.get("status")).isEqualTo("PENDING_PAY");
        assertThat(created.get("payChannel")).isEqualTo("WECHAT");
        String paymentNo = String.valueOf(created.get("paymentNo"));
        assertThat(created.get("codeUrl").toString()).startsWith("weixin://wxpay/bizpayurl?pr=MOCK_");
        assertThat(created.get("codeUrl").toString()).contains(paymentNo);

        Map<String, Object> pending = data(
                get("/api/v1/f/payments/" + paymentNo, frontToken()), HttpStatus.OK);
        assertThat(pending.get("status")).isEqualTo("PENDING");

        ResponseEntity<Map<String, Object>> notify = rest.exchange(
                "/api/v1/pay/wechat/notify",
                HttpMethod.POST,
                new HttpEntity<>(notifyBody(paymentNo, 19800), jsonHeaders(null)),
                MAP);
        assertThat(notify.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> paid = data(
                get("/api/v1/f/payments/" + paymentNo, frontToken()), HttpStatus.OK);
        assertThat(paid.get("status")).isEqualTo("SUCCESS");
        String orderId = String.valueOf(created.get("orderId"));
        assertThat(occupyStore.findOrderById(Long.parseLong(orderId)).status()).isEqualTo("BOOKED");

        Map<String, Object> checked = data(post(
                "/api/v1/f/orders/" + orderId + "/check-in",
                Map.of("requestId", "ci-wx", "verify", "PHONE", "keyword", "18600002222"),
                frontToken()), HttpStatus.OK);
        assertThat(checked.get("status")).isEqualTo("CHECKED_IN");
        assertThat(checked.get("customerMask")).isEqualTo("186****2222");
    }

    @Test
    void walkInReusesCustomerByPhone() {
        Map<String, Object> first = data(post("/api/v1/f/walk-ins", walkIn(
                "wi-merge-a", "18600003333", "散客A", 48, false, "CASH"), frontToken()), HttpStatus.CREATED);
        Map<String, Object> second = data(post("/api/v1/f/walk-ins", walkIn(
                "wi-merge-b", "18600003333", "散客A", 56, false, "CASH"), frontToken()), HttpStatus.CREATED);
        assertThat(first.get("customerId")).isEqualTo(second.get("customerId"));
        assertThat(first.get("orderId")).isNotEqualTo(second.get("orderId"));
        assertThat(second.get("status")).isEqualTo("BOOKED");
    }

    @Test
    void lookupByOrderNoAndPhone() {
        Map<String, Object> created = data(post("/api/v1/f/walk-ins", walkIn(
                "wi-lookup", "18600004444", "赵先生", 60, true, "CASH"), frontToken()), HttpStatus.CREATED);
        String orderNo = String.valueOf(created.get("orderNo"));

        Map<String, Object> byNo = data(
                get("/api/v1/f/orders/lookup?verify=ORDER_NO&keyword=" + orderNo, frontToken()), HttpStatus.OK);
        assertThat(items(byNo)).hasSize(1);
        assertThat(items(byNo).getFirst().get("orderNo")).isEqualTo(orderNo);

        Map<String, Object> byPhone = data(
                get("/api/v1/f/orders/lookup?verify=PHONE&keyword=18600004444", frontToken()), HttpStatus.OK);
        assertThat(items(byPhone)).hasSize(1);
        assertThat(items(byPhone).getFirst().get("customerMask")).isEqualTo("186****4444");
    }

    @Test
    void walkInRejectsMissingPhoneAndBadChannel() {
        Map<String, Object> noPhone = walkIn("wi-nophone", "18600005555", "x", 44, true, "CASH");
        noPhone.put("phone", "");
        ResponseEntity<Map<String, Object>> missing = post("/api/v1/f/walk-ins", noPhone, frontToken());
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getBody()).isNotNull();
        assertThat(missing.getBody().get("code")).isEqualTo(40001);

        ResponseEntity<Map<String, Object>> bad = post("/api/v1/f/walk-ins",
                walkIn("wi-card", "18600005555", "x", 44, true, "CARD"), frontToken());
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody()).isNotNull();
        assertThat(bad.getBody().get("code")).isEqualTo(40001);
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
