package com.jisuodashi.admin;

import com.jisuodashi.catalog.DemoFixtures;
import com.jisuodashi.auth.DemoStaffIds;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.JwtService;
import com.jisuodashi.auth.TokenType;
import com.jisuodashi.catalog.CatalogRepository;
import com.jisuodashi.catalog.DemoCatalogIds;
import com.jisuodashi.rbac.RbacDemoIds;
import com.jisuodashi.rbac.ScopedStoreDirectory;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class AdminCatalogApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JwtService jwt;

    @Autowired
    private CatalogRepository catalog;

    @Autowired
    private ScopedStoreDirectory stores;

    @BeforeEach
    @AfterEach
    void reset() {
        catalog.resetDemo();
        stores.resetDemo();
    }

    @Test
    void listsSeedCatalog() {
        Map<String, Object> storesData = data(get("/api/v1/a/stores", admin()));
        assertThat(items(storesData)).hasSize(2);
        assertThat(items(storesData)).extracting(i -> i.get("code")).containsExactly("DEMO01", "DEMO02");

        List<Map<String, Object>> therapists = items(data(get("/api/v1/a/therapists", admin())));
        assertThat(therapists).hasSize(DemoFixtures.therapists().size());
        assertThat(therapists).extracting(i -> i.get("name")).startsWith("林晓", "陈默", "周可");

        List<Map<String, Object>> projects = items(data(get("/api/v1/a/projects", admin())));
        assertThat(projects).hasSize(4); // P60 / P45 / P90 / P120
        assertThat(projects).extracting(i -> i.get("code")).contains("P60", "P45", "P90");

        List<Map<String, Object>> templates = items(data(get("/api/v1/a/schedule-templates", admin())));
        assertThat(templates).hasSize(DemoFixtures.therapists().size() * 7);
        assertThat(templates.getFirst().get("templateId"))
                .isEqualTo(String.valueOf(DemoCatalogIds.templateId(DemoCatalogIds.THERAPIST_LIN, 1)));
    }

    @Test
    void storeCrud() {
        ResponseEntity<Map<String, Object>> created = rest.exchange(
                "/api/v1/a/stores",
                HttpMethod.POST,
                json(Map.of("code", "DEMO99", "name", "演示新店", "status", 1), admin()),
                MAP);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> item = data(created);
        String id = String.valueOf(item.get("storeId"));
        assertThat(item.get("code")).isEqualTo("DEMO99");

        Map<String, Object> listed = data(get("/api/v1/a/stores", admin()));
        assertThat(items(listed)).extracting(i -> i.get("code")).contains("DEMO99");

        Map<String, Object> updated = data(exchange(
                "/api/v1/a/stores/" + id, HttpMethod.PUT, Map.of("name", "演示新店改名"), admin()));
        assertThat(updated.get("name")).isEqualTo("演示新店改名");

        ResponseEntity<Map<String, Object>> deleted = rest.exchange(
                "/api/v1/a/stores/" + id, HttpMethod.DELETE, json(null, admin()), MAP);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(items(data(get("/api/v1/a/stores", admin()))))
                .extracting(i -> i.get("code"))
                .doesNotContain("DEMO99");

        ResponseEntity<Map<String, Object>> reuse = rest.exchange(
                "/api/v1/a/stores",
                HttpMethod.POST,
                json(Map.of("code", "DEMO99", "name", "复用编码"), admin()),
                MAP);
        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reuse.getBody().get("code")).isEqualTo(40001);
        assertThat(String.valueOf(reuse.getBody().get("message"))).contains("占用");
    }

    @Test
    void therapistProjectTemplateCrudAndBufferCap() {
        ResponseEntity<Map<String, Object>> therapist = rest.exchange(
                "/api/v1/a/therapists",
                HttpMethod.POST,
                json(Map.of(
                        "employeeNo", "T099",
                        "name", "测试技师",
                        "homeStoreId", String.valueOf(DemoCatalogIds.STORE),
                        "level", "JUNIOR",
                        "intro", "试岗"), admin()),
                MAP);
        assertThat(therapist.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String therapistId = String.valueOf(data(therapist).get("therapistId"));

        ResponseEntity<Map<String, Object>> badBuffer = rest.exchange(
                "/api/v1/a/projects",
                HttpMethod.POST,
                json(Map.of(
                        "code", "P99",
                        "name", "超缓冲",
                        "durationMinutes", 60,
                        "bufferMinutes", 20,
                        "priceFen", 10000), admin()),
                MAP);
        assertThat(badBuffer.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badBuffer.getBody().get("code")).isEqualTo(40001);
        assertThat(String.valueOf(badBuffer.getBody().get("message"))).contains("buffer");

        ResponseEntity<Map<String, Object>> badDuration = rest.exchange(
                "/api/v1/a/projects",
                HttpMethod.POST,
                json(Map.of(
                        "code", "P16",
                        "name", "非15倍数",
                        "durationMinutes", 16,
                        "bufferMinutes", 15,
                        "priceFen", 10000), admin()),
                MAP);
        assertThat(badDuration.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badDuration.getBody().get("code")).isEqualTo(40001);
        assertThat(String.valueOf(badDuration.getBody().get("message"))).contains("15");

        ResponseEntity<Map<String, Object>> project = rest.exchange(
                "/api/v1/a/projects",
                HttpMethod.POST,
                json(Map.of(
                        "code", "P30",
                        "name", "足部放松",
                        "durationMinutes", 30,
                        "bufferMinutes", 15,
                        "priceFen", 8800), admin()),
                MAP);
        assertThat(project.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String projectId = String.valueOf(data(project).get("projectId"));
        assertThat(data(project).get("bufferMinutes")).isEqualTo(15);

        ResponseEntity<Map<String, Object>> template = rest.exchange(
                "/api/v1/a/schedule-templates",
                HttpMethod.POST,
                json(Map.of(
                        "therapistId", therapistId,
                        "storeId", String.valueOf(DemoCatalogIds.STORE),
                        "weekday", 1,
                        "startTime", "11:00",
                        "endTime", "19:00",
                        "effectiveFrom", "2026-08-01"), admin()),
                MAP);
        assertThat(template.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String templateId = String.valueOf(data(template).get("templateId"));
        assertThat(data(template).get("startTime")).isEqualTo("11:00");

        Map<String, Object> patched = data(exchange(
                "/api/v1/a/schedule-templates/" + templateId,
                HttpMethod.PUT,
                Map.of("endTime", "20:00"),
                admin()));
        assertThat(patched.get("endTime")).isEqualTo("20:00");

        rest.exchange("/api/v1/a/schedule-templates/" + templateId, HttpMethod.DELETE, json(null, admin()), MAP);
        rest.exchange("/api/v1/a/projects/" + projectId, HttpMethod.DELETE, json(null, admin()), MAP);
        rest.exchange("/api/v1/a/therapists/" + therapistId, HttpMethod.DELETE, json(null, admin()), MAP);

        ResponseEntity<Map<String, Object>> gone = get("/api/v1/a/therapists/" + therapistId, admin());
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(gone.getBody().get("code")).isEqualTo(40401);
        assertThat(items(data(get("/api/v1/a/therapists", admin()))))
                .extracting(i -> i.get("therapistId"))
                .doesNotContain(therapistId);
        assertThat(items(data(get("/api/v1/a/projects", admin()))))
                .extracting(i -> i.get("projectId"))
                .doesNotContain(projectId);

        ResponseEntity<Map<String, Object>> reuseEmp = rest.exchange(
                "/api/v1/a/therapists",
                HttpMethod.POST,
                json(Map.of(
                        "employeeNo", "T099",
                        "name", "复用工号",
                        "homeStoreId", String.valueOf(DemoCatalogIds.STORE),
                        "level", "JUNIOR"), admin()),
                MAP);
        assertThat(reuseEmp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reuseEmp.getBody().get("code")).isEqualTo(40001);

        ResponseEntity<Map<String, Object>> reuseCode = rest.exchange(
                "/api/v1/a/projects",
                HttpMethod.POST,
                json(Map.of(
                        "code", "P30",
                        "name", "复用编码",
                        "durationMinutes", 30,
                        "bufferMinutes", 15,
                        "priceFen", 8800), admin()),
                MAP);
        assertThat(reuseCode.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reuseCode.getBody().get("code")).isEqualTo(40001);
    }

    @Test
    void scopedAdminDoesNotSeeEastStoreTherapists() {
        String scoped = jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.ADMIN, TokenType.A, "STORE", List.of(RbacDemoIds.STORE))).token();
        List<Map<String, Object>> storeItems = items(data(get("/api/v1/a/stores", scoped)));
        assertThat(storeItems).extracting(i -> i.get("code")).containsExactly("DEMO01");

        ResponseEntity<Map<String, Object>> east = rest.exchange(
                "/api/v1/a/stores/" + RbacDemoIds.STORE_EAST + "/status",
                HttpMethod.POST,
                json(Map.of("status", 0), scoped),
                MAP);
        assertThat(east.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(east.getBody().get("code")).isEqualTo(40302);

        ResponseEntity<Map<String, Object>> create = rest.exchange(
                "/api/v1/a/stores",
                HttpMethod.POST,
                json(Map.of("code", "DEMO98", "name", "越权新店"), scoped),
                MAP);
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(create.getBody().get("code")).isEqualTo(40302);
    }

    @Test
    void managerCannotHitAdminCatalog() {
        String manager = jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.MANAGER, TokenType.F, "STORE", List.of(RbacDemoIds.STORE))).token();
        ResponseEntity<Map<String, Object>> res = get("/api/v1/a/therapists", manager);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody().get("code")).isEqualTo(40301);
    }

    private String admin() {
        return jwt.issue(JwtPrincipal.staff(DemoStaffIds.ADMIN, TokenType.A, "ALL", List.of())).token();
    }

    private ResponseEntity<Map<String, Object>> get(String path, String bearer) {
        return rest.exchange(path, HttpMethod.GET, json(null, bearer), MAP);
    }

    private ResponseEntity<Map<String, Object>> exchange(
            String path, HttpMethod method, Map<String, ?> body, String bearer) {
        return rest.exchange(path, method, json(body, bearer), MAP);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> res) {
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(0);
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
