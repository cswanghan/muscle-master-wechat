package com.jisuodashi.growth;

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

/** 回访、考勤、打卡、评估、好评认领、成果台。 */
@DevApiTest
class GrowthApiTest {

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

    // ── 考勤 ──

    @Test
    void clockInTwiceKeepsTheFirstTimeSoLatenessIsNotErasable() {
        Map<String, Object> first = data(post("/api/v1/t/attendance/clock-in", Map.of(), tToken()));
        String at = String.valueOf(first.get("clockInAt"));
        assertThat(at).isNotEqualTo("null");
        assertThat(first.get("onDuty")).isEqualTo(true);

        // 重复点不该把打卡时间往后推 —— 那等于帮人改迟到记录。
        Map<String, Object> again = data(post("/api/v1/t/attendance/clock-in", Map.of(), tToken()));
        assertThat(again.get("clockInAt")).isEqualTo(at);

        Map<String, Object> out = data(post("/api/v1/t/attendance/clock-out", Map.of(), tToken()));
        assertThat(out.get("clockOutAt")).isNotNull();
        assertThat(out.get("onDuty")).isEqualTo(false);
        assertThat(((Number) out.get("monthPresentDays")).intValue()).isEqualTo(1);
    }

    // ── 回访 ──

    @Test
    void followUpWithContentCountsAsDoneWhileAnEmptyOneStaysPending() {
        String customerId = member();

        Map<String, Object> done = data(post("/api/v1/t/follow-ups", Map.of(
                "requestId", "fu-" + SEQ.incrementAndGet(),
                "customerId", customerId,
                "kind", "AFTER_CLASS",
                "content", "已叮嘱拉伸，明天复练"), tToken()));
        // 带了内容就是电话已经打完了。
        assertThat(done.get("pending")).isEqualTo(false);

        Map<String, Object> todo = data(post("/api/v1/t/follow-ups", Map.of(
                "requestId", "fu-" + SEQ.incrementAndGet(),
                "customerId", customerId,
                "kind", "RETENTION"), tToken()));
        assertThat(todo.get("pending")).isEqualTo(true);

        Map<String, Object> list = get("/api/v1/t/follow-ups", tToken());
        assertThat(((Number) list.get("pending")).intValue()).isPositive();
        // 完成度只看"到期该做的"，还没到期的不进分母。
        assertThat(((Number) list.get("doneRateX100")).intValue()).isBetween(0, 10_000);

        Map<String, Object> closed = data(post(
                "/api/v1/t/follow-ups/" + todo.get("followUpId") + "/done",
                Map.of("content", "已联系，下周续课", "outcome", "DEAL"), tToken()));
        assertThat(closed.get("pending")).isEqualTo(false);
    }

    // ── 课后打卡 ──

    @Test
    void checkinIsOnePerDayAndSecondSubmitEditsRatherThanDuplicates() {
        String customerId = member();
        String token = cToken(customerId);

        data(post("/api/v1/c/checkins", Map.of(
                "checkDay", "2026-08-14", "content", "拉伸 20 分钟"), token));
        data(post("/api/v1/c/checkins", Map.of(
                "checkDay", "2026-08-14", "content", "改成 30 分钟"), token));

        Map<String, Object> mine = get("/api/v1/c/checkins?scope=mine", token);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) mine.get("items");
        // 一人一天一条：重复提交是改今天这条，不是再加一条。
        assertThat(items).hasSize(1);
        assertThat(items.getFirst().get("content")).isEqualTo("改成 30 分钟");
    }

    @Test
    void likingTwiceOnlyCountsOnce() {
        String a = member();
        String b = member();
        Map<String, Object> post = data(post("/api/v1/c/checkins",
                Map.of("checkDay", "2026-08-13", "content", "今日打卡"), cToken(a)));
        String id = String.valueOf(post.get("checkinId"));

        data(post("/api/v1/c/checkins/" + id + "/like", Map.of(), cToken(b)));
        Map<String, Object> second = data(post("/api/v1/c/checkins/" + id + "/like", Map.of(), cToken(b)));
        // 主键防重：同一个人点两次还是 1。
        assertThat(((Number) second.get("likeCount")).intValue()).isEqualTo(1);
    }

    // ── 评估与结果比对 ──

    @Test
    void assessmentCompareNeedsABaselineOtherwiseImprovementWouldBeFabricated() {
        String customerId = member();

        // 只有一次评估时没得比 —— 不能拿第一条凑数说"改善了多少"。
        data(post("/api/v1/t/members/" + customerId + "/assessments", Map.of(
                "phase", "MID", "assessedOn", "2026-08-01",
                "items", List.of(Map.of("name", "体前屈", "value", "5", "unit", "cm"))), tToken()));
        Map<String, Object> one = get("/api/v1/t/members/" + customerId + "/assessments", tToken());
        assertThat((List<?>) one.get("compare")).isEmpty();
    }

    @Test
    void assessmentCompareComputesSignedDeltaForNumericItems() {
        String customerId = member();
        data(post("/api/v1/t/members/" + customerId + "/assessments", Map.of(
                "phase", "BASELINE", "assessedOn", "2026-08-01",
                "items", List.of(
                        Map.of("name", "体前屈", "value", "-5", "unit", "cm"),
                        Map.of("name", "疼痛描述", "value", "刺痛", "unit", ""))), tToken()));
        data(post("/api/v1/t/members/" + customerId + "/assessments", Map.of(
                "phase", "FINAL", "assessedOn", "2026-09-01",
                "items", List.of(
                        Map.of("name", "体前屈", "value", "3", "unit", "cm"),
                        Map.of("name", "疼痛描述", "value", "酸胀", "unit", ""))), tToken()));

        Map<String, Object> res = get("/api/v1/t/members/" + customerId + "/assessments", tToken());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> compare = (List<Map<String, Object>>) res.get("compare");
        assertThat(compare).hasSize(2);

        Map<String, Object> numeric = compare.stream()
                .filter(r -> "体前屈".equals(r.get("name"))).findFirst().orElseThrow();
        assertThat(numeric.get("baseline")).isEqualTo("-5");
        assertThat(numeric.get("latest")).isEqualTo("3");
        assertThat(numeric.get("delta")).isEqualTo("+8");

        // 非数值项不硬算差值，否则会算出一个没意义的数。
        Map<String, Object> textual = compare.stream()
                .filter(r -> "疼痛描述".equals(r.get("name"))).findFirst().orElseThrow();
        assertThat(textual.get("delta")).isNull();
    }

    // ── 外部好评认领 ──

    @Test
    void claimedReviewsCountOnlyAfterApproval() {
        Map<String, Object> claim = data(post("/api/v1/t/review-claims", Map.of(
                "platform", "DIANPING", "rating", 5,
                "proofUrl", "https://example.com/a.png"), tToken()));
        assertThat(claim.get("status")).isEqualTo("PENDING");

        Map<String, Object> before = get("/api/v1/f/review-claims?status=APPROVED", fToken());
        int approvedBefore = ((Number) before.get("approved")).intValue();

        data(post("/api/v1/f/review-claims/" + claim.get("claimId") + "/approve", Map.of(), fToken()));

        Map<String, Object> after = get("/api/v1/f/review-claims?status=APPROVED", fToken());
        // 不审就计入的话，认领一张截图就能刷好评率。
        assertThat(((Number) after.get("approved")).intValue()).isEqualTo(approvedBefore + 1);
    }

    @Test
    void aClaimCannotBeDecidedTwice() {
        Map<String, Object> claim = data(post("/api/v1/t/review-claims", Map.of(
                "platform", "XIAOHONGSHU", "rating", 5), tToken()));
        data(post("/api/v1/f/review-claims/" + claim.get("claimId") + "/approve", Map.of(), fToken()));
        ResponseEntity<Map<String, Object>> again = rest.exchange(
                "/api/v1/f/review-claims/" + claim.get("claimId") + "/reject", HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers(fToken())), MAP);
        assertThat((Integer) again.getBody().get("code")).isNotZero();
    }

    // ── 成果台 ──

    @Test
    void scoreboardRanksEveryMetricSeparatelyRatherThanOneBlendedScore() {
        Map<String, Object> board = get("/api/v1/t/scoreboard", tToken());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ranks = (List<Map<String, Object>>) board.get("ranks");
        // 合成一个"综合分"会把权重藏起来，老师看不懂自己该改什么。
        assertThat(ranks).extracting(r -> r.get("metric"))
                .containsExactly("lessons", "sales", "positive", "renew", "convert");
        assertThat(ranks).allSatisfy(r -> {
            assertThat(((Number) r.get("rank")).intValue()).isPositive();
            assertThat(((Number) r.get("total")).intValue())
                    .isGreaterThanOrEqualTo(((Number) r.get("rank")).intValue());
        });
        assertThat(board.get("encouragement")).isNotNull();
    }

    @Test
    void nationalRankingSpansStoresNotJustMyOwn() {
        Map<String, Object> board = get("/api/v1/t/scoreboard", tToken());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ranks = (List<Map<String, Object>>) board.get("ranks");
        int total = ((Number) ranks.getFirst().get("total")).intValue();
        // 演示数据两家店各 10 位老师；只按本店排的话总数会是 10。
        assertThat(total).isGreaterThan(10);
    }

    // ── helpers ──

    private String member() {
        int n = SEQ.incrementAndGet();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", "wi-g-" + n);
        m.put("phone", "1881000" + String.format("%04d", n));
        m.put("customerName", "成长会员" + n);
        m.put("therapistId", String.valueOf(DemoCatalogIds.THERAPIST_LIN));
        m.put("projectId", String.valueOf(DemoCatalogIds.PROJECT_P60));
        m.put("date", "2026-08-14");
        m.put("startSlotNo", 40 + (n % 8) * 6);
        m.put("alreadyInStore", true);
        m.put("payChannel", "CASH");
        Map<String, Object> wi = data(rest.exchange("/api/v1/f/walk-ins", HttpMethod.POST,
                new HttpEntity<>(m, headers(fToken())), MAP));
        String customerId = String.valueOf(wi.get("customerId"));
        // 建档，否则回访和打卡都找不到这个人。
        data(post("/api/v1/t/members/" + customerId + "/profile",
                Map.of("coreIssue", "肩颈劳损", "channel", "XIAOHONGSHU"), tToken()));
        return customerId;
    }

    private ResponseEntity<Map<String, Object>> post(String path, Map<String, ?> body, String token) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(token)), MAP);
    }

    private Map<String, Object> get(String path, String token) {
        return data(rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)), MAP));
    }

    private String tToken() {
        return jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.T1, TokenType.T, "SELF", List.of(DemoCatalogIds.STORE))).token();
    }

    private String fToken() {
        return jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.MANAGER, TokenType.F, "STORE", List.of(DemoCatalogIds.STORE))).token();
    }

    private String cToken(String customerId) {
        return jwt.issue(JwtPrincipal.customer(Long.parseLong(customerId))).token();
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
