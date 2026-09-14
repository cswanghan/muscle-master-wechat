package com.jisuodashi.finance;

import com.jisuodashi.DevApiTest;
import com.jisuodashi.auth.DemoStaffIds;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.JwtService;
import com.jisuodashi.auth.TokenType;
import com.jisuodashi.catalog.DemoCatalogIds;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 支出审批与六张报表。 */
@DevApiTest
class FinanceApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {
            };

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JwtService jwt;

    @Autowired
    private InMemorySlotOccupyStore occupyStore;

    @BeforeEach
    void reset() {
        occupyStore.resetDemoCalendar();
    }

    /** 本类会建真订单占掉演示日的库存，跑完还原，免得脏给后面的用例。 */
    @org.junit.jupiter.api.AfterEach
    void cleanUp() {
        occupyStore.resetDemoCalendar();
    }

    // ── 支出审批 ──

    @Test
    void submitterCannotApproveTheirOwnExpense() {
        Map<String, Object> e = data(post("/api/v1/f/finance/expenses", Map.of(
                "category", "MATERIAL", "amountFen", 50_000,
                "vendor", "某某商贸", "proofUrl", "https://example.com/p.png"), managerToken()));
        assertThat(e.get("status")).isEqualTo("PENDING");

        // 一个人既能提又能批，凭证审批就只是走个形式。
        ResponseEntity<Map<String, Object>> self = rest.exchange(
                "/api/v1/f/finance/expenses/" + e.get("expenseId") + "/approve", HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers(managerToken())), MAP);
        assertThat((Integer) self.getBody().get("code")).isNotZero();
    }

    @Test
    void onlyApprovedExpensesCountTowardCost() {
        long before = fen(get("/api/v1/f/finance/reports/store", financeToken()), "expenseTotalYuan");

        Map<String, Object> e = data(post("/api/v1/f/finance/expenses", Map.of(
                "category", "MATERIAL", "amountFen", 100_000), managerToken()));
        // 还没批就计入的话，谁都能把利润表填花。
        long pending = fen(get("/api/v1/f/finance/reports/store", financeToken()), "expenseTotalYuan");
        assertThat(pending).isEqualTo(before);

        data(post("/api/v1/f/finance/expenses/" + e.get("expenseId") + "/approve",
                Map.of(), financeToken()));
        long after = fen(get("/api/v1/f/finance/reports/store", financeToken()), "expenseTotalYuan");
        assertThat(after).isEqualTo(before + 100_000);
    }

    @Test
    void unknownCategoryIsRejected() {
        ResponseEntity<Map<String, Object>> res = rest.exchange(
                "/api/v1/f/finance/expenses", HttpMethod.POST,
                new HttpEntity<>(Map.of("category", "COFFEE", "amountFen", 100),
                        headers(managerToken())), MAP);
        assertThat((Integer) res.getBody().get("code")).isNotZero();
    }

    // ── 报表 ──

    @Test
    void inventoryReportValuesUnfinishedSessionsAsLiability() {
        String phone = member(40);
        data(sell(phone, 10, 300_000));

        Map<String, Object> inv = get("/api/v1/f/finance/reports/inventory", financeToken());
        // 10 次 × 300 元 = 3000 元还没上的课，这是负债不是资产。
        assertThat(fen(inv, "totalValueYuan")).isGreaterThanOrEqualTo(300_000);
        assertThat(((Number) inv.get("totalSessions")).intValue()).isGreaterThanOrEqualTo(10);
    }

    @Test
    void storeReportProfitIsIncomeMinusExpense() {
        Map<String, Object> r = get("/api/v1/f/finance/reports/store", financeToken());
        long income = fen(r, "incomeTotalYuan");
        long expense = fen(r, "expenseTotalYuan");
        long profit = fen(r, "profitYuan");
        assertThat(profit).isEqualTo(income - expense);
        assertThat(r.get("profitable")).isEqualTo(profit >= 0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> exp = (List<Map<String, Object>>) r.get("expense");
        // 支出侧要把七个类目都列出来，即使是 0 —— 缺行会让人以为漏记了。
        assertThat(exp).extracting(x -> x.get("key"))
                .contains("MATERIAL", "RENT", "PROPERTY", "UTILITY", "LABOR", "PROMOTION", "REFUND");
    }

    @Test
    void payrollUsesDefaultsWhenNoConfigExists() {
        Map<String, Object> r = get("/api/v1/f/finance/reports/payroll", financeToken());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) r.get("rows");
        assertThat(rows).isNotEmpty();
        // 没配薪资的老师走默认底薪，不是 0。
        assertThat(rows).allSatisfy(x ->
                assertThat(fenOf(String.valueOf(x.get("baseSalaryYuan")))).isPositive());
    }

    @Test
    void payrollConfigOverridesTheDefault() {
        // 这个口子返回的是 "ok" 字符串而不是对象，别套 data()。
        post("/api/v1/f/finance/payroll-config", Map.of(
                "therapistId", String.valueOf(DemoCatalogIds.THERAPIST_LIN),
                "baseSalaryFen", 800_000, "lessonFeeFen", 5_000,
                "saleRateX100", 1200), financeToken());

        Map<String, Object> r = get("/api/v1/f/finance/reports/payroll", financeToken());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) r.get("rows");
        Map<String, Object> lin = rows.stream()
                .filter(x -> String.valueOf(DemoCatalogIds.THERAPIST_LIN).equals(x.get("therapistId")))
                .findFirst().orElseThrow();
        assertThat(fenOf(String.valueOf(lin.get("baseSalaryYuan")))).isEqualTo(800_000);
        assertThat(((Number) lin.get("saleRateX100")).intValue()).isEqualTo(1200);
    }

    @Test
    void customerReportSplitsRenewFromDormant() {
        for (String kind : List.of("renew", "expired", "dormant")) {
            Map<String, Object> r = get("/api/v1/f/finance/reports/customers?kind=" + kind, financeToken());
            assertThat(r.get("kind")).isEqualTo(kind);
            assertThat(r.get("kindLabel")).isNotNull();
            assertThat(((Number) r.get("total")).intValue()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void trialReportBreaksDownByAcquisitionChannel() {
        Map<String, Object> r = get("/api/v1/f/finance/reports/trial", financeToken());
        assertThat(r.get("month")).isNotNull();
        assertThat(((Number) r.get("convertRateX100")).intValue()).isBetween(0, 10_000);
        assertThat(r.get("byChannel")).isInstanceOf(List.class);
    }

    @Test
    void consumeReportIsEmptyBeforeAnyLessonIsFinished() {
        Map<String, Object> r = get("/api/v1/f/finance/reports/consume", financeToken());
        // 耗课才是收入：课没上完，这张表就该是空的。
        assertThat(((Number) r.get("totalLessons")).intValue()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void superAdminWithNoStoreScopeStillSeesReports() {
        // 超管数据域是 ALL，storeIds 是**空的** —— 直接取 getFirst() 会让
        // 权限最大的人在每张按门店的报表上撞到"请指定门店"。
        String superAdmin = jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.ADMIN, TokenType.A, "ALL", List.of())).token();
        Map<String, Object> r = get("/api/v1/f/finance/reports/store", superAdmin);
        assertThat(r.get("month")).isNotNull();
        assertThat(r.get("incomeTotalYuan")).isNotNull();

        // 门店切换器也要给得出候选，否则超管只能永远看第一家。
        ResponseEntity<Map<String, Object>> stores = rest.exchange(
                "/api/v1/f/finance/stores", HttpMethod.GET,
                new HttpEntity<>(headers(superAdmin)), MAP);
        assertThat(stores.getBody().get("code")).isEqualTo(0);
    }

    // ── helpers ──

    private ResponseEntity<Map<String, Object>> sell(String phone, int sessions, long priceFen) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", "fin-sell-" + SEQ.incrementAndGet());
        m.put("phone", phone);
        m.put("projectId", String.valueOf(DemoCatalogIds.PROJECT_P60));
        m.put("totalSessions", sessions);
        m.put("priceFen", priceFen);
        return rest.exchange("/api/v1/f/packages", HttpMethod.POST,
                new HttpEntity<>(m, headers(managerToken())), MAP);
    }

    private String member(int slotNo) {
        int n = SEQ.incrementAndGet();
        String phone = "1891000" + String.format("%04d", n);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", "wi-fin-" + n);
        m.put("phone", phone);
        m.put("customerName", "财务会员" + n);
        m.put("therapistId", String.valueOf(DemoCatalogIds.THERAPIST_LIN));
        m.put("projectId", String.valueOf(DemoCatalogIds.PROJECT_P60));
        m.put("date", "2026-08-14");
        m.put("startSlotNo", slotNo);
        m.put("alreadyInStore", true);
        m.put("payChannel", "CASH");
        data(rest.exchange("/api/v1/f/walk-ins", HttpMethod.POST,
                new HttpEntity<>(m, headers(managerToken())), MAP));
        return phone;
    }

    private ResponseEntity<Map<String, Object>> post(String path, Map<String, ?> body, String token) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(token)), MAP);
    }

    private Map<String, Object> get(String path, String token) {
        return data(rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)), MAP));
    }

    private static long fen(Map<String, Object> m, String key) {
        return fenOf(String.valueOf(m.get(key)));
    }

    private static long fenOf(String yuan) {
        return Math.round(Double.parseDouble(yuan) * 100);
    }

    private String managerToken() {
        return jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.MANAGER, TokenType.F, "STORE", List.of(DemoCatalogIds.STORE))).token();
    }

    /** 财务角色：有审批与报表权限，且不是提报人，可以合法审批。 */
    private String financeToken() {
        return jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.ADMIN, TokenType.A, "STORE", List.of(DemoCatalogIds.STORE))).token();
    }

    private static HttpHeaders headers(String bearer) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            h.setBearerAuth(bearer);
        }
        return h;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> res) {
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(0);
        return (Map<String, Object>) res.getBody().get("data");
    }
}
