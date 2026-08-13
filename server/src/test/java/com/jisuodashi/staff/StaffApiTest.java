package com.jisuodashi.staff;

import com.jisuodashi.auth.Customer;
import com.jisuodashi.auth.CustomerRepository;
import com.jisuodashi.auth.DemoStaffIds;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.JwtService;
import com.jisuodashi.auth.TokenType;
import com.jisuodashi.catalog.DemoCatalogIds;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.order.FireContext;
import com.jisuodashi.order.OrderEvent;
import com.jisuodashi.order.OrderStateMachine;
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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class StaffApiTest {

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
    private InMemoryTreatmentNoteRepository notes;

    @Autowired
    private OrderStateMachine machine;

    @Autowired
    private AppClock clock;

    @Autowired
    private CustomerRepository customers;

    @BeforeEach
    void reset() {
        occupyStore.resetDemoCalendar();
        notes.clearAdded();
        Customer guest = new Customer();
        guest.setId(8_100_000_000_000_000_001L);
        guest.setNickname("王先生");
        guest.setCreatedAt(Instant.parse("2026-08-01T00:00:00Z"));
        guest.setUpdatedAt(Instant.parse("2026-08-01T00:00:00Z"));
        customers.insert(guest);
    }

    @Test
    void todayRequiresTherapistJwt() {
        ResponseEntity<Map<String, Object>> anon = get("/api/v1/t/today", null);
        assertThat(anon.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anon.getBody().get("code")).isEqualTo(40101);

        ResponseEntity<Map<String, Object>> front = get("/api/v1/t/today", frontToken());
        assertThat(front.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(front.getBody().get("code")).isEqualTo(40301);

        ResponseEntity<Map<String, Object>> customer = get(
                "/api/v1/t/today", jwt.issue(JwtPrincipal.customer(8_100_000_000_000_000_001L)).token());
        assertThat(customer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(customer.getBody().get("code")).isEqualTo(40301);
    }

    @Test
    void todayReturnsNextJobCard() {
        Prepared order = prepareCheckedIn(78);
        Map<String, Object> data = dataOk(get("/api/v1/t/today", therapistToken()));
        @SuppressWarnings("unchecked")
        Map<String, Object> next = (Map<String, Object>) data.get("next");
        assertThat(next.get("orderId")).isEqualTo(order.orderId);
        assertThat(next.get("customerName")).isEqualTo("王先生");
        assertThat(next.get("start")).isEqualTo("19:30");
        assertThat(next.get("end")).isEqualTo("20:30");
        assertThat(next.get("projectName")).isEqualTo("全身推拿放松");
        assertThat(next.get("roomName")).isEqualTo("一号房");
        assertThat(next.get("bedName")).isIn("1号床", "2号床");
        assertThat(next.get("isNewCustomer")).isEqualTo(true);
        assertThat(next.get("status")).isEqualTo("CHECKED_IN");
        assertThat(next.containsKey("phone")).isFalse();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> timeline = (List<Map<String, Object>>) data.get("timeline");
        assertThat(timeline).isNotEmpty();
        assertThat(timeline).anyMatch(slot -> order.orderId.equals(String.valueOf(slot.get("orderId"))));
    }

    @Test
    void startAndCompleteFireStateMachine() {
        Prepared order = prepareCheckedIn(60);
        Map<String, Object> started = dataOk(post(
                "/api/v1/t/orders/" + order.orderId + "/start",
                Map.of("requestId", "t-start-1"),
                therapistToken()));
        assertThat(started.get("status")).isEqualTo("IN_SERVICE");
        assertThat(occupyStore.findOrderById(Long.parseLong(order.orderId)).status()).isEqualTo("IN_SERVICE");

        Map<String, Object> replay = dataOk(post(
                "/api/v1/t/orders/" + order.orderId + "/start",
                Map.of("requestId", "t-start-1-again"),
                therapistToken()));
        assertThat(replay.get("status")).isEqualTo("IN_SERVICE");

        Map<String, Object> done = dataOk(post(
                "/api/v1/t/orders/" + order.orderId + "/complete",
                Map.of("requestId", "t-complete-1"),
                therapistToken()));
        assertThat(done.get("status")).isEqualTo("COMPLETED");
        assertThat(occupyStore.findOrderById(Long.parseLong(order.orderId)).status()).isEqualTo("COMPLETED");

        Map<String, Object> replayDone = dataOk(post(
                "/api/v1/t/orders/" + order.orderId + "/complete",
                Map.of("requestId", "t-complete-1-again"),
                therapistToken()));
        assertThat(replayDone.get("status")).isEqualTo("COMPLETED");
    }

    @Test
    void otherTherapistCannotStart() {
        Prepared order = prepareCheckedIn(64);
        ResponseEntity<Map<String, Object>> res = post(
                "/api/v1/t/orders/" + order.orderId + "/start",
                Map.of("requestId", "t2-start"),
                t2Token());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code")).isEqualTo(40904);
        assertThat(occupyStore.findOrderById(Long.parseLong(order.orderId)).status()).isEqualTo("CHECKED_IN");
    }

    @Test
    void notesAreAppendOnlyAndTherapistOnly() {
        Prepared order = prepareCheckedIn(52);
        dataOk(post(
                "/api/v1/t/orders/" + order.orderId + "/start",
                Map.of("requestId", "t-note-start"),
                therapistToken()));

        Map<String, Object> first = dataOk(post(
                "/api/v1/t/orders/" + order.orderId + "/notes",
                Map.of("content", "腰段张力高，本次以放松为主。"),
                therapistToken()));
        assertThat(first.get("content")).isEqualTo("腰段张力高，本次以放松为主。");
        assertThat(first.get("orderId")).isEqualTo(order.orderId);

        dataOk(post(
                "/api/v1/t/orders/" + order.orderId + "/notes",
                Map.of("content", "禁忌：孕。追加一条。"),
                therapistToken()));

        Map<String, Object> listed = dataOk(get("/api/v1/t/orders/" + order.orderId + "/notes", therapistToken()));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) listed.get("items");
        assertThat(items).hasSize(2);
        assertThat(items).extracting(i -> i.get("content"))
                .containsExactly("腰段张力高，本次以放松为主。", "禁忌：孕。追加一条。");

        ResponseEntity<Map<String, Object>> other = get("/api/v1/t/orders/" + order.orderId + "/notes", t2Token());
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(other.getBody().get("code")).isEqualTo(40401);

        ResponseEntity<Map<String, Object>> manager = get("/api/v1/t/orders/" + order.orderId + "/notes", frontToken());
        assertThat(manager.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(manager.getBody().get("code")).isEqualTo(40301);

        ResponseEntity<Map<String, Object>> otherWrite = post(
                "/api/v1/t/orders/" + order.orderId + "/notes",
                Map.of("content", "不该写"),
                t2Token());
        assertThat(otherWrite.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(otherWrite.getBody().get("code")).isEqualTo(40401);
    }

    @Test
    void emptyTodayHasNullNext() {
        Map<String, Object> data = dataOk(get("/api/v1/t/today", t2Token()));
        assertThat(data.get("next")).isNull();
        assertThat(data.get("timeline")).isInstanceOf(List.class);
    }

    private Prepared prepareCheckedIn(int startSlotNo) {
        String token = jwt.issue(JwtPrincipal.customer(8_100_000_000_000_000_001L)).token();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "staff-book-" + SEQ.incrementAndGet());
        body.put("storeId", String.valueOf(DemoCatalogIds.STORE));
        body.put("therapistId", String.valueOf(DemoCatalogIds.THERAPIST_LIN));
        body.put("projectId", String.valueOf(DemoCatalogIds.PROJECT_P60));
        body.put("date", clock.today().toString());
        body.put("startSlotNo", startSlotNo);
        Map<String, Object> created = dataCreated(post("/api/v1/c/bookings", body, token));
        String orderId = created.get("orderId").toString();
        long id = Long.parseLong(orderId);
        machine.fire(id, OrderEvent.PAY_SUCCESS, FireContext.system().withPaymentMatched(true));
        machine.fire(id, OrderEvent.CHECK_IN, FireContext.system().withFrontDesk());
        assertThat(occupyStore.findOrderById(id).status()).isEqualTo("CHECKED_IN");
        return new Prepared(orderId);
    }

    private String therapistToken() {
        return jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.T1, TokenType.T, "SELF", List.of(DemoStaffIds.STORE))).token();
    }

    private String t2Token() {
        return jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.T2, TokenType.T, "SELF", List.of(DemoStaffIds.STORE))).token();
    }

    private String frontToken() {
        return jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.FRONT, TokenType.F, "STORE", List.of(DemoStaffIds.STORE))).token();
    }

    private ResponseEntity<Map<String, Object>> get(String path, String bearer) {
        return rest.exchange(path, HttpMethod.GET, json(null, bearer), MAP);
    }

    private ResponseEntity<Map<String, Object>> post(String path, Map<String, ?> body, String bearer) {
        return rest.exchange(path, HttpMethod.POST, json(body, bearer), MAP);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOk(ResponseEntity<Map<String, Object>> res) {
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(0);
        return (Map<String, Object>) res.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataCreated(ResponseEntity<Map<String, Object>> res) {
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(0);
        return (Map<String, Object>) res.getBody().get("data");
    }

    private static HttpEntity<?> json(Map<String, ?> body, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers);
    }

    private record Prepared(String orderId) {
    }
}
