package com.jisuodashi.membership;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 课包全链路：卖课 → 用课包约课 → 上完课扣一次 → 老师端看得到。 */
@DevApiTest
class MembershipApiTest {

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

    @Test
    void sellingAPackageSplitsUnitPriceAndOpensAProfile() {
        String phone = member(60);
        Map<String, Object> pkg = data(sell(phone, 10, 300_000, null));

        assertThat(pkg.get("packageId")).isNotNull();
        assertThat(pkg.get("totalSessions")).isEqualTo(10);
        // 3000 元 10 次 = 单次 300 元；退课按单次价折算，所以这个数必须落库。
        assertThat(pkg.get("unitPriceYuan")).isEqualTo("300.00");
        // 卖课提成 8% 只认实收，不含赠课。
        assertThat(((Number) pkg.get("saleCommissionFen")).longValue()).isEqualTo(24_000);

        // 卖课顺手建档，否则这位会员在老师端的"我的会员"里是隐身的。
        Map<String, Object> detail = get("/api/v1/t/members/" + pkg.get("customerId"));
        assertThat(((Number) detail.get("remainingSessions")).intValue()).isEqualTo(10);
        assertThat(((Number) detail.get("doneSessions")).intValue()).isZero();
    }

    @Test
    void unitPriceRoundsUpSoRefundsNeverOverpay() {
        String phone = member(62);
        // 1000 元 3 次除不尽：单价向上取整到 333.34，保证 单价×次数 ≥ 实收。
        Map<String, Object> pkg = data(sell(phone, 3, 100_000, null));
        assertThat(pkg.get("unitPriceYuan")).isEqualTo("333.34");
    }

    @Test
    void finishingALessonConsumesExactlyOneSession() {
        String phone = member(64);
        Map<String, Object> pkg = data(sell(phone, 10, 300_000,
                String.valueOf(DemoCatalogIds.THERAPIST_LIN)));
        String customerId = String.valueOf(pkg.get("customerId"));

        Map<String, Object> booked = bookWithPackage(
                customerId, String.valueOf(pkg.get("packageId")), 70);
        // 课包付的单直接就是已预约，没有微信那条腿。
        assertThat(booked.get("status")).isEqualTo("BOOKED");
        assertThat(((Number) booked.get("payableFen")).longValue()).isZero();

        checkInStartComplete(booked);

        Map<String, Object> detail = get("/api/v1/t/members/" + customerId);
        assertThat(((Number) detail.get("remainingSessions")).intValue()).isEqualTo(9);
        assertThat(((Number) detail.get("doneSessions")).intValue()).isEqualTo(1);
    }

    @Test
    void bookingIsRejectedWhenTheCardIsEmpty() {
        String phone = member(66);
        Map<String, Object> pkg = data(sell(phone, 1, 30_000, null));
        String customerId = String.valueOf(pkg.get("customerId"));
        String packageId = String.valueOf(pkg.get("packageId"));

        checkInStartComplete(bookWithPackage(customerId, packageId, 72));

        // 余次归零后再约，必须挡下来 —— 不挡的话会产生一张永远扣不到课时的单。
        ResponseEntity<Map<String, Object>> res = bookRaw(customerId, packageId, 76);
        assertThat(res.getBody()).isNotNull();
        assertThat((Integer) res.getBody().get("code")).isNotZero();
    }

    @Test
    void aPackageCannotBeSpentByAnotherMember() {
        Map<String, Object> mine = data(sell(member(40), 10, 300_000, null));
        String otherCustomer = String.valueOf(data(sell(member(46), 10, 300_000, null)).get("customerId"));

        ResponseEntity<Map<String, Object>> res =
                bookRaw(otherCustomer, String.valueOf(mine.get("packageId")), 52);
        assertThat((Integer) res.getBody().get("code")).isNotZero();
    }

    @Test
    void memberListShowsRemainingDoneAndPlanProgress() {
        String phone = member(68);
        Map<String, Object> pkg = data(sell(phone, 10, 300_000,
                String.valueOf(DemoCatalogIds.THERAPIST_LIN)));
        String customerId = String.valueOf(pkg.get("customerId"));

        // 归属老师 + 核心问题由带课的老师填。
        data(post("/api/v1/t/members/" + customerId + "/profile",
                Map.of("coreIssue", "腰椎间盘突出", "remark", "避免负重深蹲"), therapistToken()));

        Map<String, Object> list = get("/api/v1/t/members");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) list.get("items");
        Map<String, Object> row = items.stream()
                .filter(i -> customerId.equals(i.get("customerId")))
                .findFirst()
                .orElseThrow();

        assertThat(row.get("coreIssue")).isEqualTo("腰椎间盘突出");
        assertThat(((Number) row.get("remainingSessions")).intValue()).isEqualTo(10);
        assertThat(row.get("phoneMask").toString()).contains("****");
    }

    @Test
    void daySlotsShowBothFreeAndBookedSoTheDayIsLegible() {
        Map<String, Object> day = get("/api/v1/t/day-slots?date=2026-08-14");
        assertThat(day.get("date")).isEqualTo("2026-08-14");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slots = (List<Map<String, Object>>) day.get("slots");
        assertThat(slots).isNotEmpty();
        // 只给空档等于把老师自己的课表藏起来；忙的时段也要在。
        assertThat(slots).anySatisfy(s -> assertThat(s.get("bookable")).isEqualTo(true));
        assertThat(((Number) day.get("freeCount")).intValue()).isPositive();
    }

    @Test
    void sellingToANonMemberIsRejected() {
        ResponseEntity<Map<String, Object>> res = rest.exchange(
                "/api/v1/f/packages", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "phone", "13700000000",
                        "projectId", String.valueOf(DemoCatalogIds.PROJECT_P60),
                        "totalSessions", 10,
                        "priceFen", 300_000), headers(frontToken())),
                MAP);
        assertThat((Integer) res.getBody().get("code")).isNotZero();
    }

    // --- helpers ---

    private ResponseEntity<Map<String, Object>> sell(
            String phone, int sessions, long priceFen, String seller) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", "sell-" + SEQ.incrementAndGet());
        m.put("phone", phone);
        m.put("projectId", String.valueOf(DemoCatalogIds.PROJECT_P60));
        m.put("totalSessions", sessions);
        m.put("priceFen", priceFen);
        m.put("sellerTherapistId", seller);
        return rest.exchange("/api/v1/f/packages", HttpMethod.POST,
                new HttpEntity<>(m, headers(frontToken())), MAP);
    }

    private ResponseEntity<Map<String, Object>> bookRaw(
            String customerId, String packageId, int slotNo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", "pkg-book-" + SEQ.incrementAndGet());
        m.put("storeId", String.valueOf(DemoCatalogIds.STORE));
        m.put("therapistId", String.valueOf(DemoCatalogIds.THERAPIST_LIN));
        m.put("projectId", String.valueOf(DemoCatalogIds.PROJECT_P60));
        m.put("date", "2026-08-14");
        m.put("startSlotNo", slotNo);
        m.put("memberPackageId", packageId);
        return rest.exchange("/api/v1/c/bookings", HttpMethod.POST,
                new HttpEntity<>(m, headers(customerToken(customerId))), MAP);
    }

    private Map<String, Object> bookWithPackage(String customerId, String packageId, int slotNo) {
        ResponseEntity<Map<String, Object>> res = bookRaw(customerId, packageId, slotNo);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> d = (Map<String, Object>) res.getBody().get("data");
        return d;
    }

    /** 核销按订单号，跟前台真实操作一致。 */
    private void checkInStartComplete(Map<String, Object> booked) {
        String orderId = String.valueOf(booked.get("orderId"));
        data(post("/api/v1/f/orders/" + orderId + "/check-in",
                Map.of("requestId", "ci-" + SEQ.incrementAndGet(),
                        "verify", "ORDER_NO", "keyword", String.valueOf(booked.get("orderNo"))),
                frontToken()));
        data(post("/api/v1/t/orders/" + orderId + "/start",
                Map.of("requestId", "st-" + SEQ.incrementAndGet()), therapistToken()));
        data(post("/api/v1/t/orders/" + orderId + "/complete",
                Map.of("requestId", "cp-" + SEQ.incrementAndGet()), therapistToken()));
    }

    private ResponseEntity<Map<String, Object>> post(String path, Map<String, ?> body, String token) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(token)), MAP);
    }

    private Map<String, Object> get(String path) {
        return data(rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(headers(therapistToken())), MAP));
    }

    /** 建一个真会员：课包只认已在系统里的顾客，散客开单顺带把手机号绑上。 */
    private String member(int startSlotNo) {
        int n = SEQ.incrementAndGet();
        String phone = "1871000" + String.format("%04d", n);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", "wi-pkg-" + n);
        m.put("phone", phone);
        m.put("customerName", "会员" + n);
        m.put("therapistId", String.valueOf(DemoCatalogIds.THERAPIST_LIN));
        m.put("projectId", String.valueOf(DemoCatalogIds.PROJECT_P60));
        m.put("date", "2026-08-14");
        m.put("startSlotNo", startSlotNo);
        m.put("alreadyInStore", true);
        m.put("payChannel", "CASH");
        ResponseEntity<Map<String, Object>> res = rest.exchange(
                "/api/v1/f/walk-ins", HttpMethod.POST,
                new HttpEntity<>(m, headers(frontToken())), MAP);
        assertThat(res.getBody().get("code")).isEqualTo(0);
        return phone;
    }

    private String customerToken(String customerId) {
        return jwt.issue(JwtPrincipal.customer(Long.parseLong(customerId))).token();
    }

    private String frontToken() {
        return jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.FRONT, TokenType.F, "STORE", List.of(DemoCatalogIds.STORE))).token();
    }

    private String therapistToken() {
        return jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.T1, TokenType.T, "SELF", List.of(DemoCatalogIds.STORE))).token();
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
