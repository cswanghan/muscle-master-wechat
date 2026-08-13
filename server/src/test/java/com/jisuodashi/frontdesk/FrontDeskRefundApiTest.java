package com.jisuodashi.frontdesk;

import com.jisuodashi.auth.DemoStaffIds;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.JwtService;
import com.jisuodashi.auth.TokenType;
import com.jisuodashi.catalog.DemoCatalogIds;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.order.FireContext;
import com.jisuodashi.order.OrderEvent;
import com.jisuodashi.order.OrderStateMachine;
import com.jisuodashi.payment.InMemoryPaymentStore;
import com.jisuodashi.payment.MockWeChatPayClient;
import com.jisuodashi.payment.Payment;
import com.jisuodashi.payment.Refund;
import com.jisuodashi.payment.WeChatPayClient;
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

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class FrontDeskRefundApiTest {

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
    private OrderStateMachine machine;

    @Autowired
    private WeChatPayClient wechat;

    @BeforeEach
    void reset() {
        occupyStore.resetDemoCalendar();
        payments.clear();
        if (wechat instanceof MockWeChatPayClient mock) {
            mock.resetRefunds();
        }
    }

    @Test
    void bookedWechatRefundReleasesSlots() {
        String customer = customerToken();
        Map<String, Object> created = data(post("/api/v1/c/bookings", booking("fd-rf-1", 64), customer),
                HttpStatus.CREATED);
        long orderId = Long.parseLong(String.valueOf(created.get("orderId")));
        Map<String, Object> pay = data(post("/api/v1/c/bookings/" + orderId + "/pay",
                Map.of("requestId", "pay-fd-1"), customer), HttpStatus.OK);
        notify(String.valueOf(pay.get("paymentNo")), 19800);
        assertThat(occupyStore.findOrderById(orderId).status()).isEqualTo("BOOKED");
        int occ = occupyStore.occupancyCount();
        assertThat(occ).isGreaterThan(0);

        Map<String, Object> refunded = data(post(
                "/api/v1/f/orders/" + orderId + "/refund",
                refundBody("fd-rf-req-1", 19800),
                frontToken()), HttpStatus.OK);
        assertThat(refunded.get("status")).isEqualTo("CANCELLED");
        assertThat(items(refunded, "refunds")).hasSize(1);
        assertThat(items(refunded, "refunds").getFirst().get("status")).isEqualTo(Refund.SUCCESS);
        assertThat(occupyStore.findOrderById(orderId).status()).isEqualTo("CANCELLED");
        assertThat(occupyStore.occupancyCount()).isZero();
        assertThat(((MockWeChatPayClient) wechat).refundCalls()).hasSize(1);
    }

    @Test
    void twoSuccessPaymentsYieldTwoRefunds() {
        String customer = customerToken();
        Map<String, Object> created = data(post("/api/v1/c/bookings", booking("fd-rf-2", 60), customer),
                HttpStatus.CREATED);
        long orderId = Long.parseLong(String.valueOf(created.get("orderId")));
        Map<String, Object> pay = data(post("/api/v1/c/bookings/" + orderId + "/pay",
                Map.of("requestId", "pay-fd-2"), customer), HttpStatus.OK);
        notify(String.valueOf(pay.get("paymentNo")), 19800);
        insertAddon(orderId, 9900);

        Map<String, Object> refunded = data(post(
                "/api/v1/f/orders/" + orderId + "/refund",
                refundBody("fd-rf-req-2", 29700),
                frontToken()), HttpStatus.OK);
        assertThat(items(refunded, "refunds")).hasSize(2);
        assertThat(((MockWeChatPayClient) wechat).refundCalls()).hasSize(2);
    }

    @Test
    void cashRefundSkipsWechat() {
        Map<String, Object> created = data(post("/api/v1/f/walk-ins", walkIn(
                "fd-rf-cash", "18600007777", "退款客", 56, false, "CASH"), frontToken()), HttpStatus.CREATED);
        long orderId = Long.parseLong(String.valueOf(created.get("orderId")));
        Map<String, Object> refunded = data(post(
                "/api/v1/f/orders/" + orderId + "/refund",
                refundBody("fd-rf-cash-1", 19800),
                frontToken()), HttpStatus.OK);
        assertThat(items(refunded, "refunds")).hasSize(1);
        assertThat(items(refunded, "refunds").getFirst().get("status")).isEqualTo(Refund.SUCCESS);
        assertThat(((MockWeChatPayClient) wechat).refundCalls()).isEmpty();
    }

    @Test
    void sameRequestIdReplayIsIdempotent() {
        String customer = customerToken();
        Map<String, Object> created = data(post("/api/v1/c/bookings", booking("fd-rf-id", 52), customer),
                HttpStatus.CREATED);
        long orderId = Long.parseLong(String.valueOf(created.get("orderId")));
        Map<String, Object> pay = data(post("/api/v1/c/bookings/" + orderId + "/pay",
                Map.of("requestId", "pay-fd-id"), customer), HttpStatus.OK);
        notify(String.valueOf(pay.get("paymentNo")), 19800);

        Map<String, Object> first = data(post(
                "/api/v1/f/orders/" + orderId + "/refund",
                refundBody("same-rf", 19800),
                frontToken()), HttpStatus.OK);
        Map<String, Object> again = data(post(
                "/api/v1/f/orders/" + orderId + "/refund",
                refundBody("same-rf", 19800),
                frontToken()), HttpStatus.OK);
        assertThat(again.get("replay")).isEqualTo(true);
        assertThat(items(again, "refunds").getFirst().get("refundNo"))
                .isEqualTo(items(first, "refunds").getFirst().get("refundNo"));
        assertThat(payments.listRefundsByOrderId(orderId)).hasSize(1);
        assertThat(((MockWeChatPayClient) wechat).refundCalls()).hasSize(1);
    }

    @Test
    void inServiceWithoutAfterStartIs40904() {
        String customer = customerToken();
        Map<String, Object> created = data(post("/api/v1/c/bookings", booking("fd-rf-is", 48), customer),
                HttpStatus.CREATED);
        long orderId = Long.parseLong(String.valueOf(created.get("orderId")));
        Map<String, Object> pay = data(post("/api/v1/c/bookings/" + orderId + "/pay",
                Map.of("requestId", "pay-fd-is"), customer), HttpStatus.OK);
        notify(String.valueOf(pay.get("paymentNo")), 19800);
        machine.fire(orderId, OrderEvent.CHECK_IN, FireContext.staff(
                DemoStaffIds.FRONT, List.of(DemoCatalogIds.STORE)).withFrontDesk());
        machine.fire(orderId, OrderEvent.START_SERVICE, FireContext.staff(
                DemoCatalogIds.THERAPIST_LIN, List.of(DemoCatalogIds.STORE)));
        assertThat(occupyStore.findOrderById(orderId).status()).isEqualTo("IN_SERVICE");

        ResponseEntity<Map<String, Object>> res = post(
                "/api/v1/f/orders/" + orderId + "/refund",
                refundBody("fd-rf-is-1", 19800),
                frontToken());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(40904);
        assertThat(payments.listRefundsByOrderId(orderId)).isEmpty();
    }

    @Test
    void pendingPayRefundIs40904() {
        String customer = customerToken();
        Map<String, Object> created = data(post("/api/v1/c/bookings", booking("fd-rf-pp", 44), customer),
                HttpStatus.CREATED);
        long orderId = Long.parseLong(String.valueOf(created.get("orderId")));
        ResponseEntity<Map<String, Object>> res = post(
                "/api/v1/f/orders/" + orderId + "/refund",
                refundBody("fd-rf-pp-1", 19800),
                frontToken());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(40904);
    }

    private void insertAddon(long orderId, long amountFen) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 14, 19, 0);
        long id = 88_000_000_000_000_000L + amountFen;
        payments.beginWork();
        payments.insert(new Payment(
                id, "P" + id, orderId, Payment.CHANNEL_WECHAT, amountFen, Payment.SUCCESS,
                "prepay-add", "txn-add", now, null, now.plusHours(2), now, now));
        payments.commitWork();
    }

    private void notify(String paymentNo, long amountFen) {
        rest.exchange(
                "/api/v1/pay/wechat/notify",
                HttpMethod.POST,
                new HttpEntity<>(notifyBody(paymentNo, amountFen), jsonHeaders(null)),
                MAP);
    }

    private String customerToken() {
        return jwt.issue(JwtPrincipal.customer(8_100_000_000_000_000_001L)).token();
    }

    private String frontToken() {
        return jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.FRONT, TokenType.F, "STORE", List.of(DemoCatalogIds.STORE))).token();
    }

    private static Map<String, Object> refundBody(String requestId, long amountFen) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", requestId);
        m.put("amountFen", amountFen);
        m.put("reason", "客户改期无法改约");
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
    private static List<Map<String, Object>> items(Map<String, Object> data, String key) {
        return (List<Map<String, Object>>) data.get(key);
    }
}
