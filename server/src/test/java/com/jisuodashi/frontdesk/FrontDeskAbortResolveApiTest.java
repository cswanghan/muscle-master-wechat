package com.jisuodashi.frontdesk;

import com.jisuodashi.auth.CustomerRepository;
import com.jisuodashi.auth.DemoStaffIds;
import com.jisuodashi.auth.HumanTask;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.JwtService;
import com.jisuodashi.auth.TokenType;
import com.jisuodashi.catalog.DemoCatalogIds;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.order.FireContext;
import com.jisuodashi.order.OrderEvent;
import com.jisuodashi.order.OrderStateMachine;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** §3 {@code IN_SERVICE → ABNORMAL → COMPLETED/CANCELLED} 闭环：前台中止，店长出度。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class FrontDeskAbortResolveApiTest {

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
    void abortOpensAbnormalTaskAndReplaysOnRetry() {
        long orderId = inServiceWalkIn("ab-open", "18600001111", 44);

        Map<String, Object> aborted = data(post(
                "/api/v1/f/orders/" + orderId + "/abort",
                abort("ab-open-1", "客人身体不适中止"),
                frontToken()), HttpStatus.OK);
        assertThat(aborted.get("status")).isEqualTo("ABNORMAL");
        assertThat(aborted.get("replay")).isEqualTo(false);
        String taskId = String.valueOf(aborted.get("taskId"));
        assertThat(taskId).isNotEqualTo("null");
        assertThat(occupyStore.findOrderById(orderId).status()).isEqualTo("ABNORMAL");

        HumanTask task = task(orderId);
        assertThat(task.getTaskType()).isEqualTo(FrontDeskService.TASK_ORDER_ABNORMAL);
        assertThat(task.getStatus()).isEqualTo("OPEN");
        assertThat(task.getBizKey()).isEqualTo(FrontDeskService.ABNORMAL_BIZ_PREFIX + orderId);
        assertThat(task.getDetail()).contains("客人身体不适中止");
        assertThat(task.getStoreId()).isEqualTo(DemoCatalogIds.STORE);

        Map<String, Object> again = data(post(
                "/api/v1/f/orders/" + orderId + "/abort",
                abort("ab-open-2", "重复提交"),
                frontToken()), HttpStatus.OK);
        assertThat(again.get("replay")).isEqualTo(true);
        assertThat(again.get("status")).isEqualTo("ABNORMAL");
        assertThat(again.get("taskId")).isEqualTo(taskId);
        assertThat(payments.listHumanTasks().stream()
                .filter(t -> FrontDeskService.TASK_ORDER_ABNORMAL.equals(t.getTaskType()))
                .count()).isEqualTo(1);
    }

    @Test
    void managerResolveCompleteFinishesOrderAndClosesTask() {
        long orderId = inServiceWalkIn("ab-done", "18600002222", 48);
        String taskId = abortAndTaskId(orderId, "ab-done-1");

        Map<String, Object> resolved = data(post(
                "/api/v1/f/human-tasks/" + taskId + "/resolve",
                resolve("rs-done-1", FrontDeskService.ACTION_RESOLVE_COMPLETE, "已补做完成"),
                managerToken()), HttpStatus.OK);
        assertThat(resolved.get("action")).isEqualTo(FrontDeskService.ACTION_RESOLVE_COMPLETE);
        assertThat(resolved.get("taskStatus")).isEqualTo("DONE");
        assertThat(resolved.get("orderStatus")).isEqualTo("COMPLETED");
        assertThat(resolved.get("replay")).isEqualTo(false);
        assertThat(occupyStore.findOrderById(orderId).status()).isEqualTo("COMPLETED");
        assertThat(task(orderId).getStatus()).isEqualTo("DONE");
        assertThat(task(orderId).getResolvedBy()).isEqualTo(DemoStaffIds.MANAGER);
    }

    @Test
    void resolveReplayKeepsFinalStateInsteadOfConflict() {
        long orderId = inServiceWalkIn("ab-replay", "18600003333", 52);
        String taskId = abortAndTaskId(orderId, "ab-replay-1");
        data(post("/api/v1/f/human-tasks/" + taskId + "/resolve",
                resolve("rs-replay-1", FrontDeskService.ACTION_RESOLVE_COMPLETE, null),
                managerToken()), HttpStatus.OK);

        Map<String, Object> again = data(post(
                "/api/v1/f/human-tasks/" + taskId + "/resolve",
                resolve("rs-replay-2", FrontDeskService.ACTION_RESOLVE_CANCEL, null),
                managerToken()), HttpStatus.OK);
        assertThat(again.get("replay")).isEqualTo(true);
        assertThat(again.get("taskStatus")).isEqualTo("DONE");
        assertThat(again.get("orderStatus")).isEqualTo("COMPLETED");
        assertThat(occupyStore.findOrderById(orderId).status()).isEqualTo("COMPLETED");
    }

    @Test
    void resolveCancelMovesOrderToCancelled() {
        long orderId = inServiceWalkIn("ab-cancel", "18600004444", 56);
        String taskId = abortAndTaskId(orderId, "ab-cancel-1");

        Map<String, Object> resolved = data(post(
                "/api/v1/f/human-tasks/" + taskId + "/resolve",
                resolve("rs-cancel-1", FrontDeskService.ACTION_RESOLVE_CANCEL, "无法继续，走退款"),
                managerToken()), HttpStatus.OK);
        assertThat(resolved.get("orderStatus")).isEqualTo("CANCELLED");
        assertThat(resolved.get("taskStatus")).isEqualTo("DONE");
        assertThat(occupyStore.findOrderById(orderId).status()).isEqualTo("CANCELLED");
    }

    @Test
    void ignoreClosesTaskWithoutTouchingOrder() {
        long orderId = inServiceWalkIn("ab-ignore", "18600005555", 60);
        String taskId = abortAndTaskId(orderId, "ab-ignore-1");

        Map<String, Object> resolved = data(post(
                "/api/v1/f/human-tasks/" + taskId + "/resolve",
                resolve("rs-ignore-1", FrontDeskService.ACTION_IGNORE, "误触，线下已处理"),
                managerToken()), HttpStatus.OK);
        assertThat(resolved.get("taskStatus")).isEqualTo("IGNORED");
        assertThat(resolved.get("orderStatus")).isEqualTo("ABNORMAL");
        assertThat(occupyStore.findOrderById(orderId).status()).isEqualTo("ABNORMAL");
        assertThat(task(orderId).getStatus()).isEqualTo("IGNORED");
    }

    @Test
    void frontDeskCanAbortButCannotResolve() {
        long orderId = inServiceWalkIn("ab-perm", "18600006666", 64);
        String taskId = abortAndTaskId(orderId, "ab-perm-1");

        ResponseEntity<Map<String, Object>> denied = post(
                "/api/v1/f/human-tasks/" + taskId + "/resolve",
                resolve("rs-perm-1", FrontDeskService.ACTION_RESOLVE_COMPLETE, null),
                frontToken());
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody()).isNotNull();
        assertThat(denied.getBody().get("code")).isEqualTo(40301);
        assertThat(occupyStore.findOrderById(orderId).status()).isEqualTo("ABNORMAL");
        assertThat(task(orderId).getStatus()).isEqualTo("OPEN");
    }

    @Test
    void otherStoreManagerCannotResolve() {
        long orderId = inServiceWalkIn("ab-scope", "18600007777", 68);
        String taskId = abortAndTaskId(orderId, "ab-scope-1");

        String other = jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.MANAGER, TokenType.F, "STORE", List.of(99L))).token();
        ResponseEntity<Map<String, Object>> denied = post(
                "/api/v1/f/human-tasks/" + taskId + "/resolve",
                resolve("rs-scope-1", FrontDeskService.ACTION_IGNORE, null),
                other);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody()).isNotNull();
        assertThat(denied.getBody().get("code")).isEqualTo(40302);
        assertThat(task(orderId).getStatus()).isEqualTo("OPEN");
    }

    @Test
    void unknownActionIsRejected() {
        long orderId = inServiceWalkIn("ab-action", "18600008888", 72);
        String taskId = abortAndTaskId(orderId, "ab-action-1");

        ResponseEntity<Map<String, Object>> bad = post(
                "/api/v1/f/human-tasks/" + taskId + "/resolve",
                resolve("rs-action-1", "RESOLVE_WHATEVER", null),
                managerToken());
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody()).isNotNull();
        assertThat(bad.getBody().get("code")).isEqualTo(40001);
        assertThat(task(orderId).getStatus()).isEqualTo("OPEN");
    }

    @Test
    void resolveUnknownTaskIsNotFound() {
        ResponseEntity<Map<String, Object>> missing = post(
                "/api/v1/f/human-tasks/9007199254740991/resolve",
                resolve("rs-missing-1", FrontDeskService.ACTION_IGNORE, null),
                managerToken());
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody()).isNotNull();
        assertThat(missing.getBody().get("code")).isEqualTo(40401);
    }

    private String abortAndTaskId(long orderId, String requestId) {
        Map<String, Object> aborted = data(post(
                "/api/v1/f/orders/" + orderId + "/abort",
                abort(requestId, "中止"),
                frontToken()), HttpStatus.OK);
        assertThat(aborted.get("status")).isEqualTo("ABNORMAL");
        return String.valueOf(aborted.get("taskId"));
    }

    private HumanTask task(long orderId) {
        return payments.listHumanTasks().stream()
                .filter(t -> (FrontDeskService.ABNORMAL_BIZ_PREFIX + orderId).equals(t.getBizKey()))
                .findFirst()
                .orElseThrow();
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

    private String managerToken() {
        return jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.MANAGER, TokenType.F, "STORE", List.of(DemoCatalogIds.STORE))).token();
    }

    private static Map<String, Object> walkIn(String requestId, String phone, int startSlotNo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", requestId);
        m.put("phone", phone);
        m.put("customerName", "异常单客");
        m.put("therapistId", String.valueOf(DemoCatalogIds.THERAPIST_LIN));
        m.put("projectId", String.valueOf(DemoCatalogIds.PROJECT_P60));
        m.put("date", "2026-08-14");
        m.put("startSlotNo", startSlotNo);
        m.put("alreadyInStore", true);
        m.put("payChannel", "CASH");
        return m;
    }

    private static Map<String, Object> abort(String requestId, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", requestId);
        m.put("reason", reason);
        return m;
    }

    private static Map<String, Object> resolve(String requestId, String action, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", requestId);
        m.put("action", action);
        m.put("note", note);
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
