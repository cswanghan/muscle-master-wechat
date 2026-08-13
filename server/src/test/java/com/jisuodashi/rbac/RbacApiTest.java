package com.jisuodashi.rbac;

import com.jisuodashi.auth.DemoStaffIds;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.JwtService;
import com.jisuodashi.auth.TokenType;
import com.jisuodashi.frontdesk.DeskNoteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@org.springframework.context.annotation.Import(UnscopedWriteFixtureController.class)
class RbacApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JwtService jwt;

    @Autowired
    private AuditLogRepository audits;

    @Autowired
    private DeskNoteService deskNotes;

    @Autowired
    private ApplicationContext context;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mapping;

    @BeforeEach
    void reset() {
        audits.clear();
        deskNotes.clear();
    }

    @Test
    void productionFaWritesMustBeStoreScoped() {
        List<String> missing = StoreScopedWriteScanner.violations(mapping);
        assertThat(missing).as("unscoped /f /a writes: %s", missing).isEmpty();
    }

    @Test
    void captchaFilterIsRegisteredAndDefaultOff() {
        assertThat(context.getBean(CaptchaFilter.class)).isNotNull();
        assertThat(context.getBean(com.jisuodashi.common.AppProperties.class)
                .getBooking().getCaptcha().isEnabled()).isFalse();
        ResponseEntity<Map<String, Object>> res = rest.exchange(
                "/api/v1/c/bookings", HttpMethod.POST, json(Map.of("x", 1), null), MAP);
        assertThat(res.getStatusCode().value()).isNotEqualTo(400);
        if (res.getBody() != null) {
            assertThat(res.getBody().get("code")).isNotEqualTo(40001);
        }
    }

    @Test
    void unscopedWriteIsRejected() {
        ResponseEntity<Map<String, Object>> res = rest.exchange(
                "/api/v1/f/_fixture/unscoped",
                HttpMethod.POST,
                json(Map.of(), managerToken()),
                MAP);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(40302);
        assertThat(String.valueOf(res.getBody().get("message"))).contains("@StoreScoped");
    }

    @Test
    void storeScopedListOnlySeesOwnStore() {
        Map<String, Object> admin = data(get("/api/v1/a/stores", adminToken()));
        assertThat(admin.get("scopeType")).isEqualTo("ALL");
        assertThat(items(admin)).hasSize(2);
        assertThat(items(admin)).extracting(i -> i.get("code")).containsExactly("DEMO01", "DEMO02");

        Map<String, Object> manager = data(get("/api/v1/f/stores", managerToken()));
        assertThat(manager.get("scopeType")).isEqualTo("STORE");
        assertThat(items(manager)).hasSize(1);
        assertThat(items(manager).getFirst().get("storeId")).isEqualTo(String.valueOf(RbacDemoIds.STORE));
        assertThat(items(manager)).extracting(i -> i.get("code")).doesNotContain("DEMO02");
    }

    @Test
    void outOfScopeWriteIs40302() {
        ResponseEntity<Map<String, Object>> res = rest.exchange(
                "/api/v1/f/desk-notes",
                HttpMethod.POST,
                json(Map.of("storeId", String.valueOf(RbacDemoIds.STORE_EAST), "content", "nope"), managerToken()),
                MAP);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody().get("code")).isEqualTo(40302);
    }

    @Test
    void inScopeWriteIsAudited() {
        ResponseEntity<Map<String, Object>> res = rest.exchange(
                "/api/v1/f/desk-notes",
                HttpMethod.POST,
                json(Map.of("storeId", String.valueOf(RbacDemoIds.STORE), "content", "到店核销"), managerToken()),
                MAP);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("code")).isEqualTo(0);
        assertThat(audits.listRecent(20))
                .anyMatch(e -> "STAFF".equals(e.getActorType()) && "POST".equals(e.getAction()));
    }

    @Test
    void treatmentNoteReadWritesAudit() {
        ResponseEntity<Map<String, Object>> res = get(
                "/api/v1/t/orders/" + RbacDemoIds.NOTE_ORDER + "/notes", therapistToken());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = res.getBody();
        assertThat(body.get("code")).isEqualTo(0);
        assertThat(audits.listRecent(20))
                .anyMatch(e -> "NOTE_READ".equals(e.getAction()) && "TREATMENT_NOTE".equals(e.getResourceType()));
    }

    @Test
    void otherTherapistCannotReadNotes() {
        String t2 = jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.T2, TokenType.T, "SELF", List.of(RbacDemoIds.STORE))).token();
        ResponseEntity<Map<String, Object>> res = get(
                "/api/v1/t/orders/" + RbacDemoIds.NOTE_ORDER + "/notes", t2);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody().get("code")).isEqualTo(40302);
    }

    @Test
    void customerJwtCannotHitFrontApi() {
        String customer = jwt.issue(JwtPrincipal.customer(99L)).token();
        ResponseEntity<Map<String, Object>> res = get("/api/v1/f/stores", customer);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody().get("code")).isEqualTo(40301);
    }

    @Test
    void missingJwtIs40101() {
        ResponseEntity<Map<String, Object>> res = get("/api/v1/f/stores", null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody().get("code")).isEqualTo(40101);
    }

    @Test
    void managerCannotCallAdminApi() {
        ResponseEntity<Map<String, Object>> res = get("/api/v1/a/stores", managerToken());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody().get("code")).isEqualTo(40301);
    }

    private String adminToken() {
        return jwt.issue(JwtPrincipal.staff(DemoStaffIds.ADMIN, TokenType.A, "ALL", List.of())).token();
    }

    private String managerToken() {
        return jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.MANAGER, TokenType.F, "STORE", List.of(RbacDemoIds.STORE))).token();
    }

    private String therapistToken() {
        return jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.T1, TokenType.T, "SELF", List.of(RbacDemoIds.STORE))).token();
    }

    private ResponseEntity<Map<String, Object>> get(String path, String bearer) {
        return rest.exchange(path, HttpMethod.GET, json(null, bearer), MAP);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ResponseEntity<Map<String, Object>> res) {
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        return (Map<String, Object>) res.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("items");
    }

    private static HttpEntity<?> json(Map<String, ?> body, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers);
    }
}
