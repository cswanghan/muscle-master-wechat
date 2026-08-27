package com.jisuodashi.card;

import com.jisuodashi.DevApiTest;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.JwtService;
import com.jisuodashi.catalog.DemoCatalogIds;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.payment.InMemoryPaymentStore;
import com.jisuodashi.payment.Payment;
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

/** 储值卡付款：全额覆盖 / 混合支付 / 关单退回 / 退款原路退回。 */
@DevApiTest
class CardPayApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {
            };

    /** P60 的价，和 DemoFixtures 里的 SKU 对齐。 */
    private static final long P60_FEN = 19_800L;

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
    private InMemoryCardStore cardStore;

    @Autowired
    private CardService cards;

    private long customerId;

    @BeforeEach
    void reset() {
        occupyStore.resetDemoCalendar();
        payments.clear();
        // 每个用例换一位顾客：卡是按 customer 唯一的，共用一张会串账。
        customerId = 8_100_000_000_000_009_000L + SEQ.incrementAndGet();
    }

    @Test
    void cardCoveringTheWholeBillLeavesNoWechatLeg() {
        topUp(30_000, 0);
        // 下单那一步就会试着付一次，卡够的话单子当场就结清了。
        Map<String, Object> created = book(70);
        String orderId = String.valueOf(created.get("orderId"));

        // 全额卡付没有微信这条腿，前端不该再去 requestPayment。
        assertThat(created.get("payParams")).isNull();
        // 状态得如实说 BOOKED：照抄下单那一刻的快照会让客户端拿到一个"待支付"的已付单。
        assertThat(created.get("status")).isEqualTo("BOOKED");

        assertThat(status(orderId)).isEqualTo("BOOKED");
        assertThat(balance()).isEqualTo(30_000 - P60_FEN);
        assertThat(successChannels(orderId)).containsExactly(Payment.CHANNEL_CARD);
    }

    @Test
    void shortCardSplitsTheBillAndWechatCoversTheRest() {
        topUp(5_000, 0);
        Map<String, Object> created = book(72);
        String orderId = String.valueOf(created.get("orderId"));
        assertThat(created.get("payParams")).isInstanceOf(Map.class);
        assertThat(created.get("status")).isEqualTo("PENDING_PAY");

        Map<String, Object> pay = pay(orderId);
        // 微信这条腿只收差额，不是全款 —— 收全款就等于卡白扣了。
        assertThat(((Number) pay.get("amountFen")).longValue()).isEqualTo(P60_FEN - 5_000);
        assertThat(pay.get("status")).isEqualTo(Payment.PENDING);

        assertThat(balance()).isZero();
        assertThat(allChannels(orderId))
                .containsExactlyInAnyOrder(Payment.CHANNEL_CARD, Payment.CHANNEL_WECHAT);

        notifyPaid(String.valueOf(pay.get("paymentNo")), P60_FEN - 5_000);
        assertThat(status(orderId)).isEqualTo("BOOKED");
    }

    @Test
    void repeatedPayDoesNotDeductTwice() {
        topUp(5_000, 0);
        String orderId = String.valueOf(book(74).get("orderId"));

        Map<String, Object> first = pay(orderId);
        Map<String, Object> second = pay(orderId);

        assertThat(balance()).isZero();
        // 每一次重试都要落在同一个拆分上：复用同一张待支付单，金额不变。
        assertThat(second.get("paymentNo")).isEqualTo(first.get("paymentNo"));
        assertThat(second.get("amountFen")).isEqualTo(first.get("amountFen"));
        assertThat(successChannels(orderId)).containsExactly(Payment.CHANNEL_CARD);
    }

    @Test
    void bonusIsSpentBeforePrincipal() {
        // 反过来会把可退的本金花光、只留下不可退的赠送额，退卡时顾客只能退到零。
        topUp(20_000, 10_000);
        book(76);

        CardModels.Card card = cards.myCard(customerId).orElseThrow();
        assertThat(card.bonusFen()).isZero();
        assertThat(card.principalFen()).isEqualTo(30_000 - P60_FEN);
    }

    @Test
    void cancellingBeforeTheWechatLegReturnsTheCardMoney() {
        topUp(5_000, 0);
        String orderId = String.valueOf(book(78).get("orderId"));
        assertThat(balance()).isZero();

        // 卡先扣、微信后付，中间用户取消了 —— 不退回来这笔钱就卡在一张不会发生的单子上。
        ResponseEntity<Map<String, Object>> res = post(
                "/api/v1/c/bookings/" + orderId + "/cancel", Map.of("requestId", "cx-" + orderId));
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();

        assertThat(status(orderId)).isEqualTo("CLOSED");
        assertThat(balance()).isEqualTo(5_000);
    }

    @Test
    void refundOfACardPaidOrderGoesBackToTheCard() {
        topUp(30_000, 0);
        String orderId = String.valueOf(book(80).get("orderId"));
        assertThat(status(orderId)).isEqualTo("BOOKED");
        assertThat(balance()).isEqualTo(30_000 - P60_FEN);

        // 已支付的单顾客自己取消不了，走前台退款。
        ResponseEntity<Map<String, Object>> res = rest.exchange(
                "/api/v1/f/orders/" + orderId + "/refund", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "requestId", "rf-" + orderId,
                        "amountFen", P60_FEN,
                        "reason", "顾客临时有事"), headers(staffToken())),
                MAP);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(0);

        // 原路退回卡，不走微信退款接口 —— 那笔钱从来没到过微信。
        assertThat(balance()).isEqualTo(30_000);
    }

    @Test
    void refundingAMixedOrderReturnsOnlyTheCardHalfToTheCard() {
        topUp(5_000, 0);
        Map<String, Object> created = book(82);
        String orderId = String.valueOf(created.get("orderId"));

        Map<String, Object> pay = pay(orderId);
        notifyPaid(String.valueOf(pay.get("paymentNo")), P60_FEN - 5_000);
        assertThat(status(orderId)).isEqualTo("BOOKED");
        assertThat(balance()).isZero();

        ResponseEntity<Map<String, Object>> res = rest.exchange(
                "/api/v1/f/orders/" + orderId + "/refund", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "requestId", "mix-" + orderId,
                        "amountFen", P60_FEN,
                        "reason", "混合支付退款"), headers(staffToken())),
                MAP);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(0);

        // 卡那一半原路回卡，微信那一半走微信退款 —— 全退回卡等于把顾客的现金变成储值。
        assertThat(balance()).isEqualTo(5_000);
    }

    @Test
    void topUpByPhoneRejectsANonMember() {
        ResponseEntity<Map<String, Object>> res = rest.exchange(
                "/api/v1/f/cards/topup", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "phone", "13900000000",
                        "principalFen", 10_000), headers(staffToken())),
                MAP);
        assertThat(res.getBody()).isNotNull();
        assertThat((Integer) res.getBody().get("code")).isNotZero();
    }

    // --- helpers ---

    private void topUp(long principalFen, long bonusFen) {
        cards.topUp(customerId, DemoCatalogIds.STORE, principalFen, bonusFen, null,
                "seed-" + customerId + "-" + principalFen + "-" + bonusFen);
    }

    private long balance() {
        return cards.myCard(customerId).map(CardModels.Card::balanceFen).orElse(0L);
    }

    private List<String> successChannels(String orderId) {
        return payments.listByOrderId(Long.parseLong(orderId)).stream()
                .filter(Payment::success)
                .map(Payment::channel)
                .toList();
    }

    /** 含待支付的微信腿：混合支付下那条腿要等回调才 SUCCESS。 */
    private List<String> allChannels(String orderId) {
        return payments.listByOrderId(Long.parseLong(orderId)).stream()
                .filter(p -> !Payment.CLOSED.equals(p.status()))
                .map(Payment::channel)
                .distinct()
                .toList();
    }

    private Map<String, Object> book(int startSlotNo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", "card-book-" + customerId + "-" + startSlotNo);
        m.put("storeId", String.valueOf(DemoCatalogIds.STORE));
        m.put("therapistId", String.valueOf(DemoCatalogIds.THERAPIST_LIN));
        m.put("projectId", String.valueOf(DemoCatalogIds.PROJECT_P60));
        m.put("date", "2026-08-14");
        m.put("startSlotNo", startSlotNo);
        return data(post("/api/v1/c/bookings", m), HttpStatus.CREATED);
    }

    private Map<String, Object> pay(String orderId) {
        return data(post("/api/v1/c/bookings/" + orderId + "/pay",
                Map.of("requestId", "pay-" + orderId + "-" + SEQ.incrementAndGet())), HttpStatus.OK);
    }

    private void notifyPaid(String paymentNo, long amountFen) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("out_trade_no", paymentNo);
        m.put("transaction_id", "wx_" + paymentNo);
        m.put("amount_fen", amountFen);
        rest.exchange("/api/v1/pay/wechat/notify", HttpMethod.POST,
                new HttpEntity<>(m, headers(null)), MAP);
    }

    private String status(String orderId) {
        return String.valueOf(data(rest.exchange("/api/v1/c/bookings/" + orderId, HttpMethod.GET,
                new HttpEntity<>(headers(token())), MAP), HttpStatus.OK).get("status"));
    }

    private ResponseEntity<Map<String, Object>> post(String path, Map<String, ?> body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(token())), MAP);
    }

    private String token() {
        return jwt.issue(JwtPrincipal.customer(customerId)).token();
    }

    private String staffToken() {
        return jwt.issue(JwtPrincipal.staff(
                com.jisuodashi.auth.DemoStaffIds.MANAGER, com.jisuodashi.auth.TokenType.F,
                "STORE", List.of(DemoCatalogIds.STORE))).token();
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
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> res, HttpStatus expected) {
        assertThat(res.getStatusCode()).isEqualTo(expected);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(0);
        return (Map<String, Object>) res.getBody().get("data");
    }
}
