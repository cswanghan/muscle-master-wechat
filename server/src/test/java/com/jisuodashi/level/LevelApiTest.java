package com.jisuodashi.level;

import com.jisuodashi.DevApiTest;
import com.jisuodashi.auth.DemoStaffIds;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.JwtService;
import com.jisuodashi.auth.TokenType;
import com.jisuodashi.catalog.CatalogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DevApiTest
class LevelApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JwtService jwt;

    @Autowired
    private CatalogRepository catalog;

    @Test
    void pendingIsEmptyBeforeAnyScan() {
        ResponseEntity<Map<String, Object>> res = call("/api/v1/a/therapist-levels/pending", HttpMethod.GET);
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(0);
    }

    @Test
    void scanIsIdempotent() {
        ResponseEntity<Map<String, Object>> first = call("/api/v1/a/therapist-levels/scan", HttpMethod.POST);
        assertThat(first.getBody()).isNotNull();
        assertThat(first.getBody().get("code")).isEqualTo(0);

        // 同一技师同一目标档只留一条待确认，重复扫描不该再提名。
        ResponseEntity<Map<String, Object>> second = call("/api/v1/a/therapist-levels/scan", HttpMethod.POST);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) second.getBody().get("data");
        assertThat((Integer) data.get("proposed")).isZero();
    }

    private ResponseEntity<Map<String, Object>> call(String path, HttpMethod method) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(jwt.issue(
                JwtPrincipal.staff(DemoStaffIds.ADMIN, TokenType.A, "ALL", List.of())).token());
        return rest.exchange(path, method, new HttpEntity<>(null, h), MAP);
    }
}
