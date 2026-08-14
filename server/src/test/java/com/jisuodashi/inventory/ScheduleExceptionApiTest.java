package com.jisuodashi.inventory;

import com.jisuodashi.auth.DemoStaffIds;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.JwtService;
import com.jisuodashi.auth.StaffUser;
import com.jisuodashi.auth.StaffUserRepository;
import com.jisuodashi.auth.TokenType;
import com.jisuodashi.catalog.DemoCatalogIds;
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
 * §2.3 请假审批闭环：申请 → 待办 → 审批（冲突 40906 / FREE→REST）。
 * {@code /a/schedule-exceptions/{id}/approve} 与 {@code /f/human-tasks/{id}/approve} 走同一实现。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class ScheduleExceptionApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    /** Demo calendar seeds 2026-08-14 + 14 days; this one carries no fixture orders. */
    private static final String LEAVE_DATE = "2026-08-16";
    private static final long FINANCE_STAFF = 3_100_000_000_000_000_311L;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JwtService jwt;

    @Autowired
    private InMemorySlotOccupyStore occupyStore;

    @Autowired
    private InMemoryScheduleExceptionStore exceptions;

    @Autowired
    private InMemoryPaymentStore payments;

    @Autowired
    private StaffUserRepository staff;

    @BeforeEach
    void reset() {
        occupyStore.resetDemoCalendar();
        exceptions.clear();
        payments.clear();
        seedFinanceStaff();
    }

    @Test
    void approveFlipsFreeSlotsToRestAndClosesTask() {
        Map<String, Object> applied = data(post("/api/v1/a/schedule-exceptions",
                leave("lv-ok", "14:00", "16:00"), adminToken()), HttpStatus.CREATED);
        String exceptionId = String.valueOf(applied.get("id"));
        assertThat(applied.get("status")).isEqualTo(ScheduleExceptionStore.STATUS_PENDING);
        assertThat(applied.get("taskBizKey")).isEqualTo(ScheduleExceptionService.BIZ_KEY_PREFIX + exceptionId);

        Map<String, Object> queue = data(get("/api/v1/f/human-tasks?status=OPEN", managerToken()), HttpStatus.OK);
        String taskId = items(queue, "items").stream()
                .filter(t -> ScheduleExceptionService.TASK_LEAVE_APPROVE.equals(t.get("taskType")))
                .map(t -> String.valueOf(t.get("id")))
                .findFirst()
                .orElseThrow();

        Map<String, Object> approved = data(post("/api/v1/f/human-tasks/" + taskId + "/approve",
                Map.of("requestId", "lv-ap"), managerToken()), HttpStatus.OK);
        assertThat(approved.get("id")).isEqualTo(exceptionId);
        assertThat(approved.get("status")).isEqualTo(ScheduleExceptionStore.STATUS_APPROVED);
        assertThat(approved.get("restSlots")).isEqualTo(8);

        assertThat(restSlotCount(LEAVE_DATE)).isEqualTo(8);
        assertThat(occupyStore.occupancyCount()).isZero();
        assertThat(openLeaveTasks()).isEmpty();
    }

    @Test
    void approveReplayDoesNotRestMoreSlots() {
        String exceptionId = String.valueOf(data(post("/api/v1/a/schedule-exceptions",
                leave("lv-replay", "14:00", "16:00"), adminToken()), HttpStatus.CREATED).get("id"));

        data(post("/api/v1/a/schedule-exceptions/" + exceptionId + "/approve", Map.of(), adminToken()),
                HttpStatus.OK);
        Map<String, Object> again = data(post("/api/v1/a/schedule-exceptions/" + exceptionId + "/approve",
                Map.of(), adminToken()), HttpStatus.OK);

        assertThat(again.get("status")).isEqualTo(ScheduleExceptionStore.STATUS_APPROVED);
        assertThat(again.get("restSlots")).isEqualTo(0);
        assertThat(restSlotCount(LEAVE_DATE)).isEqualTo(8);
    }

    @Test
    void approveRejectsWhenRangeHasBookedSlots() {
        Map<String, Object> booked = data(post("/api/v1/c/bookings", booking("lv-busy", 56), customerToken()),
                HttpStatus.CREATED);
        assertThat(booked.get("orderId")).isNotNull();

        String exceptionId = String.valueOf(data(post("/api/v1/a/schedule-exceptions",
                leave("lv-conflict", "13:00", "16:00"), adminToken()), HttpStatus.CREATED).get("id"));

        ResponseEntity<Map<String, Object>> conflict = post(
                "/api/v1/a/schedule-exceptions/" + exceptionId + "/approve", Map.of(), adminToken());
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody()).isNotNull();
        assertThat(conflict.getBody().get("code")).isEqualTo(40906);

        assertThat(restSlotCount(LEAVE_DATE)).isZero();
        Map<String, Object> listed = data(get("/api/v1/a/schedule-exceptions?status=PENDING", adminToken()),
                HttpStatus.OK);
        assertThat(items(listed, "items")).anyMatch(e -> exceptionId.equals(e.get("id")));
        assertThat(openLeaveTasks()).hasSize(1);
    }

    @Test
    void rejectLeavesSlotsFreeAndIgnoresTask() {
        String exceptionId = String.valueOf(data(post("/api/v1/a/schedule-exceptions",
                leave("lv-reject", "14:00", "16:00"), adminToken()), HttpStatus.CREATED).get("id"));

        Map<String, Object> rejected = data(post("/api/v1/a/schedule-exceptions/" + exceptionId + "/reject",
                Map.of("note", "当日人手不足"), adminToken()), HttpStatus.OK);

        assertThat(rejected.get("status")).isEqualTo(ScheduleExceptionStore.STATUS_REJECTED);
        assertThat(restSlotCount(LEAVE_DATE)).isZero();
        assertThat(openLeaveTasks()).isEmpty();
    }

    @Test
    void refundApproverWithoutScheduleApproveIsForbidden() {
        String exceptionId = String.valueOf(data(post("/api/v1/a/schedule-exceptions",
                leave("lv-perm", "14:00", "16:00"), adminToken()), HttpStatus.CREATED).get("id"));
        String taskId = openLeaveTasks().getFirst();

        ResponseEntity<Map<String, Object>> denied = post("/api/v1/f/human-tasks/" + taskId + "/approve",
                Map.of("requestId", "lv-perm-ap"), financeToken());
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody()).isNotNull();
        assertThat(denied.getBody().get("code")).isEqualTo(40301);

        assertThat(restSlotCount(LEAVE_DATE)).isZero();
        assertThat(data(get("/api/v1/a/schedule-exceptions", adminToken()), HttpStatus.OK))
                .extracting(d -> items(d, "items").getFirst().get("status"))
                .isEqualTo(ScheduleExceptionStore.STATUS_PENDING);
        assertThat(exceptionId).isNotBlank();
    }

    @Test
    void otherStoreCannotApplyLeave() {
        String other = jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.ADMIN, TokenType.A, "STORE", List.of(99L))).token();

        ResponseEntity<Map<String, Object>> denied = post("/api/v1/a/schedule-exceptions",
                leave("lv-scope", "14:00", "16:00"), other);

        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody()).isNotNull();
        assertThat(denied.getBody().get("code")).isEqualTo(40302);
    }

    @Test
    void wholeDayLeaveRestsEveryBusinessSlot() {
        Map<String, Object> body = leave("lv-allday", null, null);
        String exceptionId = String.valueOf(
                data(post("/api/v1/a/schedule-exceptions", body, adminToken()), HttpStatus.CREATED).get("id"));

        Map<String, Object> approved = data(post("/api/v1/a/schedule-exceptions/" + exceptionId + "/approve",
                Map.of(), adminToken()), HttpStatus.OK);

        assertThat(approved.get("restSlots")).isEqualTo(48);
        assertThat(restSlotCount(LEAVE_DATE)).isEqualTo(48);
    }

    @Test
    void unalignedTimeIsRejected() {
        ResponseEntity<Map<String, Object>> bad = post("/api/v1/a/schedule-exceptions",
                leave("lv-align", "14:10", "16:00"), adminToken());

        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody()).isNotNull();
        assertThat(bad.getBody().get("code")).isEqualTo(40001);
    }

    private int restSlotCount(String date) {
        return (int) occupyStore.listTherapistSlotsByStore(DemoCatalogIds.STORE, LocalDate.parse(date)).stream()
                .filter(row -> SlotStatus.REST.equals(row.status()))
                .count();
    }

    private List<String> openLeaveTasks() {
        return payments.listHumanTasks().stream()
                .filter(t -> ScheduleExceptionService.TASK_LEAVE_APPROVE.equals(t.getTaskType()))
                .filter(t -> "OPEN".equals(t.getStatus()))
                .map(t -> String.valueOf(t.getId()))
                .toList();
    }

    private void seedFinanceStaff() {
        if (staff.findById(FINANCE_STAFF).isPresent()) {
            return;
        }
        StaffUser finance = new StaffUser();
        finance.setId(FINANCE_STAFF);
        finance.setUsername("demo.finance");
        finance.setName("演示财务");
        finance.setStatus(1);
        finance.setRoleCodes(List.of("FINANCE"));
        finance.setPermissionCodes(List.of());
        finance.setScopeType("STORE");
        finance.setStoreIds(List.of(DemoCatalogIds.STORE));
        staff.insert(finance);
    }

    private static Map<String, Object> leave(String requestId, String startTime, String endTime) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", requestId);
        m.put("therapistId", String.valueOf(DemoCatalogIds.THERAPIST_LIN));
        m.put("storeId", String.valueOf(DemoCatalogIds.STORE));
        m.put("date", LEAVE_DATE);
        m.put("type", ScheduleExceptionStore.TYPE_LEAVE);
        m.put("startTime", startTime);
        m.put("endTime", endTime);
        m.put("reason", "家中有事");
        return m;
    }

    private static Map<String, Object> booking(String requestId, int startSlotNo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", requestId);
        m.put("storeId", String.valueOf(DemoCatalogIds.STORE));
        m.put("therapistId", String.valueOf(DemoCatalogIds.THERAPIST_LIN));
        m.put("projectId", String.valueOf(DemoCatalogIds.PROJECT_P60));
        m.put("date", LEAVE_DATE);
        m.put("startSlotNo", startSlotNo);
        return m;
    }

    private String adminToken() {
        return jwt.issue(JwtPrincipal.staff(DemoStaffIds.ADMIN, TokenType.A, "ALL", List.of())).token();
    }

    private String managerToken() {
        return jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.MANAGER, TokenType.F, "STORE", List.of(DemoCatalogIds.STORE))).token();
    }

    private String financeToken() {
        return jwt.issue(JwtPrincipal.staff(
                FINANCE_STAFF, TokenType.F, "STORE", List.of(DemoCatalogIds.STORE))).token();
    }

    private String customerToken() {
        return jwt.issue(JwtPrincipal.customer(8_100_000_000_000_000_002L)).token();
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
