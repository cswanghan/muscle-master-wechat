package com.jisuodashi.admin;

import com.jisuodashi.auth.DemoStaffIds;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.JwtService;
import com.jisuodashi.auth.TokenType;
import com.jisuodashi.rbac.RbacDemoIds;
import org.junit.jupiter.api.AfterEach;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class AdminOrderApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JwtService jwt;

    @Autowired
    private AdminOrderStore orders;

    @BeforeEach
    @AfterEach
    void reset() {
        orders.resetDemo();
    }

    @Test
    void abnormalFirstIsNotCursorAndAlwaysHighlighted() {
        Map<String, Object> data = data(get("/api/v1/a/orders?view=abnormal_first", admin()));
        assertThat(data.get("view")).isEqualTo("abnormal_first");
        assertThat(data.get("nextCursor")).isNull();
        List<Map<String, Object>> items = items(data);
        assertThat(items).hasSize(2);
        assertThat(items).extracting(i -> i.get("orderId")).containsExactly(
                String.valueOf(AdminDemoIds.ORDER_MANUAL),
                String.valueOf(AdminDemoIds.ORDER_ABNORMAL));
        assertThat(items).allSatisfy(i -> {
            assertThat(i.get("highlight")).isEqualTo(true);
        });
    }

    @Test
    void defaultViewIsAbnormalFirst() {
        Map<String, Object> data = data(get("/api/v1/a/orders", admin()));
        assertThat(data.get("view")).isEqualTo("abnormal_first");
        assertThat(items(data)).hasSize(2);
    }

    @Test
    void allViewCursorsByCreatedAtIdAndHighlightsAbnormal() {
        Map<String, Object> first = data(get("/api/v1/a/orders?view=all&limit=2", admin()));
        assertThat(first.get("view")).isEqualTo("all");
        assertThat(first.get("nextCursor")).isNotNull();
        List<Map<String, Object>> page1 = items(first);
        assertThat(page1).hasSize(2);
        assertThat(page1).extracting(i -> i.get("orderId")).containsExactly(
                String.valueOf(AdminDemoIds.ORDER_SAME_SEC_HI),
                String.valueOf(AdminDemoIds.ORDER_SAME_SEC_LO));
        assertThat(String.valueOf(page1.getFirst().get("createdAt"))).contains(".500");
        assertThat(String.valueOf(page1.get(1).get("createdAt"))).contains(".200");

        List<Map<String, Object>> collected = new ArrayList<>(page1);
        String cursor = String.valueOf(first.get("nextCursor"));
        while (cursor != null && !"null".equals(cursor)) {
            Map<String, Object> next = data(get("/api/v1/a/orders?view=all&limit=2&cursor=" + cursor, admin()));
            collected.addAll(items(next));
            Object nextCursor = next.get("nextCursor");
            cursor = nextCursor == null ? null : String.valueOf(nextCursor);
        }
        assertThat(collected).hasSize(8);
        Set<Object> ids = new HashSet<>();
        collected.forEach(i -> assertThat(ids.add(i.get("orderId"))).isTrue());
        Map<String, Object> abnormal = collected.stream()
                .filter(i -> String.valueOf(AdminDemoIds.ORDER_ABNORMAL).equals(i.get("orderId")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> booked = collected.stream()
                .filter(i -> String.valueOf(AdminDemoIds.ORDER_BOOKED).equals(i.get("orderId")))
                .findFirst()
                .orElseThrow();
        assertThat(abnormal.get("highlight")).isEqualTo(true);
        assertThat(booked.get("highlight")).isEqualTo(false);
    }

    @Test
    void allViewFiltersStatusAndStore() {
        List<Map<String, Object>> booked = items(data(get("/api/v1/a/orders?view=all&status=BOOKED", admin())));
        assertThat(booked).extracting(i -> i.get("status")).containsOnly("BOOKED");
        assertThat(booked).hasSize(3);

        String scoped = jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.ADMIN, TokenType.A, "STORE", List.of(RbacDemoIds.STORE))).token();
        List<Map<String, Object>> own = items(data(get("/api/v1/a/orders?view=all", scoped)));
        assertThat(own).hasSize(7);
        assertThat(own).extracting(i -> i.get("orderId"))
                .doesNotContain(String.valueOf(AdminDemoIds.ORDER_EAST));

        ResponseEntity<Map<String, Object>> denied = get(
                "/api/v1/a/orders?view=all&storeId=" + RbacDemoIds.STORE_EAST, scoped);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().get("code")).isEqualTo(40302);
    }

    @Test
    void missingJwtAndCustomerAreRejected() {
        ResponseEntity<Map<String, Object>> anon = get("/api/v1/a/orders", null);
        assertThat(anon.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anon.getBody().get("code")).isEqualTo(40101);

        String customer = jwt.issue(JwtPrincipal.customer(99L)).token();
        ResponseEntity<Map<String, Object>> c = get("/api/v1/a/orders", customer);
        assertThat(c.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(c.getBody().get("code")).isEqualTo(40301);
    }

    @Test
    void allViewSameSecondMillisCursorKeepsSibling() {
        Map<String, Object> first = data(get("/api/v1/a/orders?view=all&limit=1", admin()));
        assertThat(items(first).getFirst().get("orderId"))
                .isEqualTo(String.valueOf(AdminDemoIds.ORDER_SAME_SEC_HI));
        String cursor = String.valueOf(first.get("nextCursor"));
        assertThat(cursor).contains("14:00:00.500");
        Map<String, Object> second = data(get("/api/v1/a/orders?view=all&limit=1&cursor=" + cursor, admin()));
        assertThat(items(second).getFirst().get("orderId"))
                .isEqualTo(String.valueOf(AdminDemoIds.ORDER_SAME_SEC_LO));
        assertThat(items(second).getFirst().get("createdAt")).isEqualTo("2026-08-14T14:00:00.200");
    }

    @Test
    void unknownViewIs40001() {
        ResponseEntity<Map<String, Object>> res = get("/api/v1/a/orders?view=mixed", admin());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("code")).isEqualTo(40001);
    }

    private String admin() {
        return jwt.issue(JwtPrincipal.staff(DemoStaffIds.ADMIN, TokenType.A, "ALL", List.of())).token();
    }

    private ResponseEntity<Map<String, Object>> get(String path, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), MAP);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> res) {
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(0);
        return (Map<String, Object>) res.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("items");
    }
}
