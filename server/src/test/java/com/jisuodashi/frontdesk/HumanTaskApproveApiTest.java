package com.jisuodashi.frontdesk;

import com.jisuodashi.auth.DemoStaffIds;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.JwtService;
import com.jisuodashi.auth.TokenType;
import com.jisuodashi.catalog.DemoCatalogIds;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.payment.InMemoryPaymentStore;
import com.jisuodashi.payment.MockWeChatPayClient;
import com.jisuodashi.payment.Payment;
import com.jisuodashi.payment.PaymentService;
import com.jisuodashi.payment.Refund;
import com.jisuodashi.payment.WeChatPayClient;
import com.jisuodashi.workflow.WorkflowInstance;
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

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class HumanTaskApproveApiTest {

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
    void amountAtLeast50000WaitsUntilApprove() {
        String customer = customerToken();
        Map<String, Object> created = data(post("/api/v1/c/bookings", booking("ht-500", 68), customer),
                HttpStatus.CREATED);
        long orderId = Long.parseLong(String.valueOf(created.get("orderId")));
        Map<String, Object> pay = data(post("/api/v1/c/bookings/" + orderId + "/pay",
                Map.of("requestId", "pay-ht-500"), customer), HttpStatus.OK);
        notify(String.valueOf(pay.get("paymentNo")), 19800);
        insertAddon(orderId, 40_000L);

        Map<String, Object> refunded = data(post(
                "/api/v1/f/orders/" + orderId + "/refund",
                refundBody("ht-rf-500", 59800),
                frontToken()), HttpStatus.OK);
        assertThat(refunded.get("workflowStatus")).isEqualTo(WorkflowInstance.WAIT_APPROVAL);
        assertThat(items(refunded, "refunds")).isNotEmpty();
        assertThat(items(refunded, "refunds").getFirst().get("status")).isEqualTo(Refund.WAIT_APPROVAL);
        assertThat(((MockWeChatPayClient) wechat).refundCalls()).isEmpty();

        Map<String, Object> listed = data(
                get("/api/v1/f/human-tasks?status=OPEN", managerToken()), HttpStatus.OK);
        List<Map<String, Object>> tasks = items(listed, "items");
        assertThat(tasks).anyMatch(t -> PaymentService.TASK_REFUND_APPROVE.equals(t.get("taskType")));
        String taskId = tasks.stream()
                .filter(t -> PaymentService.TASK_REFUND_APPROVE.equals(t.get("taskType")))
                .map(t -> String.valueOf(t.get("id")))
                .findFirst()
                .orElseThrow();

        ResponseEntity<Map<String, Object>> forbidden = post(
                "/api/v1/f/human-tasks/" + taskId + "/approve",
                Map.of("requestId", "ap-front"),
                frontToken());
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        Map<String, Object> approved = data(post(
                "/api/v1/f/human-tasks/" + taskId + "/approve",
                Map.of("requestId", "ap-mgr"),
                managerToken()), HttpStatus.OK);
        assertThat(approved.get("workflowStatus")).isEqualTo(WorkflowInstance.SUCCESS);
        assertThat(items(approved, "refunds")).allMatch(r -> Refund.SUCCESS.equals(r.get("status")));
        assertThat(((MockWeChatPayClient) wechat).refundCalls()).isNotEmpty();
    }

    @Test
    void otherStoreCannotListOrApprove() {
        String customer = customerToken();
        Map<String, Object> created = data(post("/api/v1/c/bookings", booking("ht-scope", 64), customer),
                HttpStatus.CREATED);
        long orderId = Long.parseLong(String.valueOf(created.get("orderId")));
        Map<String, Object> pay = data(post("/api/v1/c/bookings/" + orderId + "/pay",
                Map.of("requestId", "pay-ht-scope"), customer), HttpStatus.OK);
        notify(String.valueOf(pay.get("paymentNo")), 19800);
        insertAddon(orderId, 40_000L);
        data(post("/api/v1/f/orders/" + orderId + "/refund",
                refundBody("ht-rf-scope", 59800), frontToken()), HttpStatus.OK);

        String other = jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.MANAGER, TokenType.F, "STORE", List.of(99L))).token();
        Map<String, Object> listed = data(get("/api/v1/f/human-tasks?status=OPEN", other), HttpStatus.OK);
        assertThat(items(listed, "items")).noneMatch(
                t -> PaymentService.TASK_REFUND_APPROVE.equals(t.get("taskType")));

        String taskId = payments.listHumanTasks().stream()
                .filter(t -> PaymentService.TASK_REFUND_APPROVE.equals(t.getTaskType()))
                .map(t -> String.valueOf(t.getId()))
                .findFirst()
                .orElseThrow();
        ResponseEntity<Map<String, Object>> denied = post(
                "/api/v1/f/human-tasks/" + taskId + "/approve",
                Map.of("requestId", "ap-other"),
                other);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody()).isNotNull();
        assertThat(denied.getBody().get("code")).isEqualTo(40302);
    }

    @Test
    void denyCreatesVisibleManualTask() {
        String customer = customerToken();
        Map<String, Object> created = data(post("/api/v1/c/bookings", booking("ht-deny", 60), customer),
                HttpStatus.CREATED);
        long orderId = Long.parseLong(String.valueOf(created.get("orderId")));
        Map<String, Object> pay = data(post("/api/v1/c/bookings/" + orderId + "/pay",
                Map.of("requestId", "pay-ht-deny"), customer), HttpStatus.OK);
        notify(String.valueOf(pay.get("paymentNo")), 19800);
        insertAddon(orderId, 40_000L);
        data(post("/api/v1/f/orders/" + orderId + "/refund",
                refundBody("ht-rf-deny", 59800), frontToken()), HttpStatus.OK);
        String taskId = payments.listHumanTasks().stream()
                .filter(t -> PaymentService.TASK_REFUND_APPROVE.equals(t.getTaskType()))
                .map(t -> String.valueOf(t.getId()))
                .findFirst()
                .orElseThrow();

        Map<String, Object> denied = data(post(
                "/api/v1/f/human-tasks/" + taskId + "/deny",
                Map.of("requestId", "dn-mgr"),
                managerToken()), HttpStatus.OK);
        assertThat(denied.get("workflowStatus")).isEqualTo(WorkflowInstance.MANUAL);
        assertThat(items(denied, "refunds")).allMatch(r -> Refund.WAIT_APPROVAL.equals(r.get("status")));
        assertThat(((MockWeChatPayClient) wechat).refundCalls()).isEmpty();

        Map<String, Object> listed = data(
                get("/api/v1/f/human-tasks?status=OPEN", managerToken()), HttpStatus.OK);
        assertThat(items(listed, "items"))
                .anyMatch(t -> PaymentService.TASK_REFUND_DENIED.equals(t.get("taskType")));
        assertThat(occupyStore.findOrderById(orderId).status()).isEqualTo("CANCELLED");
    }

    @Test
    void approveReplayResumesPending() {
        String customer = customerToken();
        Map<String, Object> created = data(post("/api/v1/c/bookings", booking("ht-ap-re", 56), customer),
                HttpStatus.CREATED);
        long orderId = Long.parseLong(String.valueOf(created.get("orderId")));
        Map<String, Object> pay = data(post("/api/v1/c/bookings/" + orderId + "/pay",
                Map.of("requestId", "pay-ht-ap-re"), customer), HttpStatus.OK);
        notify(String.valueOf(pay.get("paymentNo")), 19800);
        insertAddon(orderId, 40_000L);
        data(post("/api/v1/f/orders/" + orderId + "/refund",
                refundBody("ht-rf-ap-re", 59800), frontToken()), HttpStatus.OK);
        var task = payments.listHumanTasks().stream()
                .filter(t -> PaymentService.TASK_REFUND_APPROVE.equals(t.getTaskType()))
                .findFirst()
                .orElseThrow();
        var now = LocalDateTime.of(2026, 8, 14, 19, 0);
        payments.beginWork();
        task.setStatus("DONE");
        payments.updateHumanTask(task);
        for (Refund row : payments.listRefundsByOrderId(orderId)) {
            payments.updateRefund(row.withStatus(Refund.PENDING, now));
        }
        var wf = payments.listWorkflowsByOrderId(orderId).getFirst();
        payments.updateWorkflow(wf.withStatus(WorkflowInstance.RUNNING, now));
        payments.commitWork();

        Map<String, Object> approved = data(post(
                "/api/v1/f/human-tasks/" + task.getId() + "/approve",
                Map.of("requestId", "ap-replay"),
                managerToken()), HttpStatus.OK);
        assertThat(approved.get("workflowStatus")).isEqualTo(WorkflowInstance.SUCCESS);
        assertThat(items(approved, "refunds")).allMatch(r -> Refund.SUCCESS.equals(r.get("status")));
        assertThat(((MockWeChatPayClient) wechat).refundCalls()).isNotEmpty();
    }

    @Test
    void wechatFailCreatesManualTask() {
        String customer = customerToken();
        Map<String, Object> created = data(post("/api/v1/c/bookings", booking("ht-fail", 72), customer),
                HttpStatus.CREATED);
        long orderId = Long.parseLong(String.valueOf(created.get("orderId")));
        Map<String, Object> pay = data(post("/api/v1/c/bookings/" + orderId + "/pay",
                Map.of("requestId", "pay-ht-fail"), customer), HttpStatus.OK);
        notify(String.valueOf(pay.get("paymentNo")), 19800);
        ((MockWeChatPayClient) wechat).failRefunds = true;

        Map<String, Object> refunded = data(post(
                "/api/v1/f/orders/" + orderId + "/refund",
                refundBody("ht-rf-fail", 19800),
                frontToken()), HttpStatus.OK);
        assertThat(refunded.get("workflowStatus")).isEqualTo(WorkflowInstance.MANUAL);
        assertThat(items(refunded, "refunds").getFirst().get("status")).isEqualTo(Refund.FAILED);

        Map<String, Object> listed = data(
                get("/api/v1/f/human-tasks?status=OPEN", managerToken()), HttpStatus.OK);
        assertThat(items(listed, "items"))
                .anyMatch(t -> PaymentService.TASK_REFUND_FAILED.equals(t.get("taskType")));
    }

    private void insertAddon(long orderId, long amountFen) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 14, 19, 0);
        long id = 88_100_000_000_000_000L + amountFen;
        payments.beginWork();
        payments.insert(new Payment(
                id, "P" + id, orderId, Payment.CHANNEL_WECHAT, amountFen, Payment.SUCCESS,
                "prepay-add", "txn-add-500", now, null, now.plusHours(2), now, now));
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

    private String managerToken() {
        return jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.MANAGER, TokenType.F, "STORE", List.of(DemoCatalogIds.STORE))).token();
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
    private static List<Map<String, Object>> items(Map<String, Object> data, String key) {
        return (List<Map<String, Object>>) data.get(key);
    }
}
