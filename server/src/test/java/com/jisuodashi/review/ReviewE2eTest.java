package com.jisuodashi.review;

import com.jisuodashi.DevApiTest;
import com.jisuodashi.auth.CustomerRepository;
import com.jisuodashi.auth.DemoStaffIds;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.JwtService;
import com.jisuodashi.auth.TokenType;
import com.jisuodashi.catalog.DemoCatalogIds;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.payment.InMemoryPaymentStore;
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

/**
 * P0 评价闭环：一单从 PENDING_PAY 走到 REVIEWED，评价落库并推进技师统计。
 * 重复提交必须返回同一条而不是报错 —— 客户端超时重发是常态。
 */
@DevApiTest
class ReviewE2eTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final AtomicInteger SEQ = new AtomicInteger();

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
    private InMemoryReviewStore reviews;

    @Autowired
    private TherapistStatService stats;

    @BeforeEach
    void reset() {
        occupyStore.resetDemoCalendar();
        payments.clear();
        customers.clear();
        reviews.clear();
    }

    @Test
    void completedOrderWalksToReviewedAndRepeatSubmitIsIdempotent() {
        String customer = login("review-e2e-1");
        long orderId = completedOrder(customer, 44, "rv-1");

        Map<String, Object> submitted = data(post("/api/v1/c/bookings/" + orderId + "/review",
                reviewBody(5, List.of("TECHNIQUE_GOOD", "ON_TIME"), "手法很到位", false),
                customer), HttpStatus.OK);

        assertThat(submitted.get("score")).isEqualTo(5);
        assertThat(submitted.get("positive")).isEqualTo(true);
        assertThat(submitted.get("tags")).isEqualTo(List.of("TECHNIQUE_GOOD", "ON_TIME"));
        assertThat(submitted.get("content")).isEqualTo("手法很到位");
        assertThat(submitted.get("therapistId")).isEqualTo(String.valueOf(DemoCatalogIds.THERAPIST_LIN));
        assertThat(occupyStore.findOrderById(orderId).status()).isEqualTo("REVIEWED");

        // 重发同一次提交：返回已有那条，不是 40904。分数换成 1 星也不该被改写。
        Map<String, Object> replay = data(post("/api/v1/c/bookings/" + orderId + "/review",
                reviewBody(1, List.of("LATE"), "改口", false), customer), HttpStatus.OK);
        assertThat(replay.get("score")).as("已评价不可覆盖").isEqualTo(5);
        assertThat(reviews.listByTherapist(DemoCatalogIds.THERAPIST_LIN, 100)).hasSize(1);

        Map<String, Object> fetched = data(get("/api/v1/c/bookings/" + orderId + "/review", customer),
                HttpStatus.OK);
        assertThat(fetched.get("score")).isEqualTo(5);
        assertThat(fetched.get("createdAt")).isNotNull();
    }

    @Test
    void reviewIsRefusedBeforeTheOrderCompletes() {
        String customer = login("review-e2e-2");
        long orderId = paidOrder(customer, 48, "rv-2");

        ResponseEntity<Map<String, Object>> tooEarly = post("/api/v1/c/bookings/" + orderId + "/review",
                reviewBody(5, List.of(), null, false), customer);
        assertThat(tooEarly.getBody().get("code")).isEqualTo(40904);

        ResponseEntity<Map<String, Object>> noReview = get("/api/v1/c/bookings/" + orderId + "/review", customer);
        assertThat(noReview.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anotherCustomersOrderIs404NotForbidden() {
        String owner = login("review-e2e-owner");
        String stranger = login("review-e2e-stranger");
        long orderId = completedOrder(owner, 52, "rv-3");

        // 40403 会告诉枚举者「这个 orderId 是真的」，只能给 40401。
        ResponseEntity<Map<String, Object>> res = post("/api/v1/c/bookings/" + orderId + "/review",
                reviewBody(5, List.of(), null, false), stranger);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody().get("code")).isEqualTo(40401);
    }

    @Test
    void tagsMustMatchTheScoreBand() {
        String customer = login("review-e2e-4");
        long orderId = completedOrder(customer, 56, "rv-4");

        ResponseEntity<Map<String, Object>> crossed = post("/api/v1/c/bookings/" + orderId + "/review",
                reviewBody(5, List.of("FORCE_TOO_HEAVY"), null, false), customer);
        assertThat(crossed.getBody().get("code")).isEqualTo(40001);

        ResponseEntity<Map<String, Object>> unknown = post("/api/v1/c/bookings/" + orderId + "/review",
                reviewBody(2, List.of("NOT_A_TAG"), null, false), customer);
        assertThat(unknown.getBody().get("code")).isEqualTo(40001);

        assertThat(occupyStore.findOrderById(orderId).status()).as("校验不通过不推状态").isEqualTo("COMPLETED");
    }

    @Test
    void submittingAReviewBumpsTheTherapistStat() {
        String customer = login("review-e2e-5");
        long orderId = completedOrder(customer, 60, "rv-5");

        var before = stats.statsFor(DemoCatalogIds.THERAPIST_CHEN);
        long chenOrder = completedOrder(customer, 64, "rv-6", DemoCatalogIds.THERAPIST_CHEN, chenToken());
        data(post("/api/v1/c/bookings/" + chenOrder + "/review",
                reviewBody(5, List.of("EFFECTIVE"), null, true), customer), HttpStatus.OK);
        var after = stats.statsFor(DemoCatalogIds.THERAPIST_CHEN);

        assertThat(after.reviewCount()).as("评价条数增量刷新，不等夜里的全量重算")
                .isEqualTo(before.reviewCount() + 1);
        assertThat(orderId).isPositive();
    }

    @Test
    void therapistUnderTheColdStartThresholdShowsCountsButNotRate() {
        // 周可种子只有 3 条评价，低于 MIN_REVIEWS_FOR_RATE。
        var zhou = stats.statsFor(DemoCatalogIds.THERAPIST_ZHOU);
        assertThat(zhou.reviewCount()).isLessThan(ReviewPolicy.MIN_REVIEWS_FOR_RATE);
        assertThat(zhou.positiveRateX100()).as("样本太少，率必须缺省而不是给 0").isNull();
        assertThat(zhou.newcomer()).isTrue();

        var lin = stats.statsFor(DemoCatalogIds.THERAPIST_LIN);
        assertThat(lin.positiveRateX100()).isNotNull();
        assertThat(lin.newcomer()).isFalse();
    }

    @Test
    void availabilityCarriesTheStatsSoTheCustomerCanCompare() {
        Map<String, Object> availability = data(get(
                "/api/v1/c/availability?storeId=" + DemoCatalogIds.STORE
                        + "&date=2026-08-14&projectId=" + DemoCatalogIds.PROJECT_P60, null), HttpStatus.OK);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> therapists = (List<Map<String, Object>>) availability.get("therapists");
        Map<String, Object> lin = therapists.stream()
                .filter(t -> String.valueOf(DemoCatalogIds.THERAPIST_LIN).equals(t.get("therapistId")))
                .findFirst().orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> stat = (Map<String, Object>) lin.get("stats");
        assertThat(stat).as("日历上就要能比，不能让客人点进详情才看见").isNotNull();
        assertThat((Integer) stat.get("repeatCount")).isPositive();
        assertThat((Integer) stat.get("reviewCount")).isPositive();
        assertThat(stat.get("positiveRateX100")).isNotNull();
    }

    private long completedOrder(String customerToken, int startSlotNo, String tag) {
        return completedOrder(customerToken, startSlotNo, tag, DemoCatalogIds.THERAPIST_LIN, linToken());
    }

    private long completedOrder(
            String customerToken, int startSlotNo, String tag, long therapistId, String therapistToken) {
        long orderId = paidOrder(customerToken, startSlotNo, tag, therapistId);
        data(post("/api/v1/t/orders/" + orderId + "/start", Map.of("requestId", tag + "-start"),
                therapistToken), HttpStatus.OK);
        Map<String, Object> done = data(post("/api/v1/t/orders/" + orderId + "/complete",
                Map.of("requestId", tag + "-done"), therapistToken), HttpStatus.OK);
        assertThat(done.get("status")).isEqualTo("COMPLETED");
        return orderId;
    }

    private long paidOrder(String customerToken, int startSlotNo, String tag) {
        return paidOrder(customerToken, startSlotNo, tag, DemoCatalogIds.THERAPIST_LIN);
    }

    private long paidOrder(String customerToken, int startSlotNo, String tag, long therapistId) {
        Map<String, Object> booked = data(post("/api/v1/c/bookings",
                booking(tag + "-book-" + SEQ.incrementAndGet(), startSlotNo, therapistId), customerToken),
                HttpStatus.CREATED);
        long orderId = Long.parseLong(String.valueOf(booked.get("orderId")));

        Map<String, Object> pay = data(post("/api/v1/c/bookings/" + orderId + "/pay",
                Map.of("requestId", tag + "-pay"), customerToken), HttpStatus.OK);
        String paymentNo = String.valueOf(pay.get("paymentNo"));

        ResponseEntity<Map<String, Object>> notify = rest.exchange(
                "/api/v1/pay/wechat/notify", HttpMethod.POST,
                new HttpEntity<>(notifyBody(paymentNo, ((Number) pay.get("amountFen")).longValue()),
                        jsonHeaders(null)),
                MAP);
        assertThat(notify.getStatusCode()).isEqualTo(HttpStatus.OK);

        data(post("/api/v1/f/orders/" + orderId + "/check-in",
                Map.of("requestId", tag + "-ci", "verify", "ORDER_NO", "keyword", booked.get("orderNo")),
                frontToken()), HttpStatus.OK);
        return orderId;
    }

    private String login(String code) {
        return String.valueOf(data(post("/api/v1/c/auth/wechat", Map.of("code", "mock:" + code), null),
                HttpStatus.OK).get("token"));
    }

    private String frontToken() {
        return jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.FRONT, TokenType.F, "STORE", List.of(DemoCatalogIds.STORE))).token();
    }

    private String linToken() {
        return jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.T1, TokenType.T, "SELF", List.of(DemoCatalogIds.STORE))).token();
    }

    private String chenToken() {
        return jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.T2, TokenType.T, "SELF", List.of(DemoCatalogIds.STORE))).token();
    }

    private static Map<String, Object> reviewBody(
            int score, List<String> tags, String content, boolean anonymous) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("score", score);
        m.put("tags", tags);
        m.put("content", content);
        m.put("anonymous", anonymous);
        return m;
    }

    private static Map<String, Object> booking(String requestId, int startSlotNo, long therapistId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", requestId);
        m.put("storeId", String.valueOf(DemoCatalogIds.STORE));
        m.put("therapistId", String.valueOf(therapistId));
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
        assertThat(res.getStatusCode()).as("body=%s", res.getBody()).isEqualTo(expected);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(0);
        return (Map<String, Object>) res.getBody().get("data");
    }
}
