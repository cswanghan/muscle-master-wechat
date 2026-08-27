package com.jisuodashi.card;

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

/** 前台储值：按手机号充值、查余额、卡销提成。 */
@DevApiTest
class CardTopUpApiTest {

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
    void topUpByPhoneSplitsPrincipalFromBonus() {
        String phone = member(60);

        Map<String, Object> res = data(topUp(phone, 50_000, 5_000, null));
        // 本金可退、赠送不可退，合成一个数就再也拆不回来。
        assertThat(((Number) res.get("principalFen")).longValue()).isEqualTo(50_000);
        assertThat(((Number) res.get("bonusFen")).longValue()).isEqualTo(5_000);
        assertThat(((Number) res.get("balanceFen")).longValue()).isEqualTo(55_000);
        assertThat(res.get("cardNo")).isNotNull();

        Map<String, Object> wallet = data(lookup(phone));
        assertThat(((Number) wallet.get("balanceFen")).longValue()).isEqualTo(55_000);
        assertThat((List<?>) wallet.get("txns")).hasSize(1);
    }

    @Test
    void topUpIsIdempotentOnRequestId() {
        String phone = member(62);
        String requestId = "tp-fixed-" + SEQ.incrementAndGet();

        data(topUpWithId(phone, 20_000, 0, null, requestId));
        Map<String, Object> again = data(topUpWithId(phone, 20_000, 0, null, requestId));

        // 重放不能再加一次余额 —— 前台网络抖一下重发就等于白送一笔。
        assertThat(((Number) again.get("balanceFen")).longValue()).isEqualTo(20_000);
    }

    @Test
    void saleCommissionCountsPrincipalOnly() {
        String phone = member(64);
        String seller = String.valueOf(DemoCatalogIds.THERAPIST_LIN);

        Map<String, Object> res = data(topUp(phone, 100_000, 30_000, seller));
        // 赠送额是营销成本，给提成等于倒贴：4% 只算在 1000 元本金上。
        assertThat(((Number) res.get("saleCommissionFen")).longValue()).isEqualTo(4_000);

        Map<String, Object> perf = data(rest.exchange(
                "/api/v1/t/performance?range=month", HttpMethod.GET,
                new HttpEntity<>(headers(therapistToken())), MAP));
        @SuppressWarnings("unchecked")
        Map<String, Object> breakdown = (Map<String, Object>) perf.get("breakdown");
        assertThat(((Number) breakdown.get("cardFen")).longValue()).isGreaterThanOrEqualTo(4_000);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) perf.get("entries");
        assertThat(entries).anySatisfy(e -> assertThat(e.get("kind")).isEqualTo("CARD_SALE"));
    }

    @Test
    void topUpWithoutSellerEarnsNobodyACommission() {
        String phone = member(66);
        Map<String, Object> before = data(rest.exchange(
                "/api/v1/t/performance?range=month", HttpMethod.GET,
                new HttpEntity<>(headers(therapistToken())), MAP));
        long was = cardFen(before);

        data(topUp(phone, 80_000, 0, null));

        Map<String, Object> after = data(rest.exchange(
                "/api/v1/t/performance?range=month", HttpMethod.GET,
                new HttpEntity<>(headers(therapistToken())), MAP));
        assertThat(cardFen(after)).isEqualTo(was);
    }

    @Test
    void zeroPrincipalIsRejected() {
        String phone = member(68);
        ResponseEntity<Map<String, Object>> res = topUp(phone, 0, 10_000, null);
        assertThat(res.getBody()).isNotNull();
        assertThat((Integer) res.getBody().get("code")).isNotZero();
    }

    @Test
    void walletOfANonMemberIs404() {
        ResponseEntity<Map<String, Object>> res = lookup("13800000001");
        assertThat(res.getBody()).isNotNull();
        assertThat((Integer) res.getBody().get("code")).isNotZero();
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private static long cardFen(Map<String, Object> summary) {
        Map<String, Object> b = (Map<String, Object>) summary.get("breakdown");
        return ((Number) b.get("cardFen")).longValue();
    }

    /** 建一个真会员：储值只认已经在系统里的顾客，散客开单顺带把手机号绑上。 */
    private String member(int startSlotNo) {
        int n = SEQ.incrementAndGet();
        String phone = "1861000" + String.format("%04d", n);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", "wi-card-" + n);
        m.put("phone", phone);
        m.put("customerName", "储值顾客" + n);
        m.put("therapistId", String.valueOf(DemoCatalogIds.THERAPIST_LIN));
        m.put("projectId", String.valueOf(DemoCatalogIds.PROJECT_P60));
        m.put("date", "2026-08-14");
        m.put("startSlotNo", startSlotNo);
        m.put("alreadyInStore", true);
        m.put("payChannel", "CASH");
        ResponseEntity<Map<String, Object>> res = rest.exchange(
                "/api/v1/f/walk-ins", HttpMethod.POST,
                new HttpEntity<>(m, headers(frontToken())), MAP);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(0);
        return phone;
    }

    private ResponseEntity<Map<String, Object>> topUp(
            String phone, long principalFen, long bonusFen, String seller) {
        return topUpWithId(phone, principalFen, bonusFen, seller, "tp-" + SEQ.incrementAndGet());
    }

    private ResponseEntity<Map<String, Object>> topUpWithId(
            String phone, long principalFen, long bonusFen, String seller, String requestId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", requestId);
        m.put("phone", phone);
        m.put("principalFen", principalFen);
        m.put("bonusFen", bonusFen);
        m.put("sellerTherapistId", seller);
        return rest.exchange("/api/v1/f/cards/topup", HttpMethod.POST,
                new HttpEntity<>(m, headers(frontToken())), MAP);
    }

    private ResponseEntity<Map<String, Object>> lookup(String phone) {
        return rest.exchange("/api/v1/f/cards/lookup?phone=" + phone, HttpMethod.POST,
                new HttpEntity<>(null, headers(frontToken())), MAP);
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
