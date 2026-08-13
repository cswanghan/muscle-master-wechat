package com.jisuodashi.auth;

import com.jisuodashi.common.PhoneCrypto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AuthApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private RelatedRecordsRepository related;

    @Autowired
    private CustomerMergeService merge;

    @Autowired
    private PhoneCrypto phoneCrypto;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private AuthSessionRepository sessions;

    @BeforeEach
    void reset() {
        customers.clear();
        related.clear();
        sessions.clear();
    }

    @Test
    void customerDevLoginIssuesTwoHourJwt() {
        ResponseEntity<Map<String, Object>> res = postJson("/api/v1/c/auth/wechat", Map.of("code", "dev"), null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("code")).isEqualTo(0);
        assertThat(body.get("requestId")).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertThat(data.get("expiresIn")).isEqualTo(7200);
        assertThat(data.get("needPhone")).isEqualTo(true);
        assertThat(data.get("token")).isInstanceOf(String.class);
        String token = (String) data.get("token");
        JwtPrincipal p = jwtService.parse(token);
        assertThat(p.typ()).isEqualTo(TokenType.C);
        assertThat(String.valueOf(p.subjectId())).isEqualTo(data.get("customerId"));
    }

    @Test
    void bindPhoneMergesWalkInAndReturnsSurvivor() {
        PhoneCrypto.PhoneParts phone = phoneCrypto.sealMobile("13800138000");
        Customer walkIn = merge.merge(null, phone.hash(), phone.cipher());
        related.addBooking(99L, walkIn.getId());

        Map<String, Object> login = data(postJson("/api/v1/c/auth/wechat", Map.of("code", "dev"), null));
        String token = (String) login.get("token");
        String openidOnlyId = (String) login.get("customerId");
        assertThat(openidOnlyId).isNotEqualTo(String.valueOf(walkIn.getId()));

        Map<String, Object> bound = data(postJson(
                "/api/v1/c/auth/bind-phone",
                Map.of("phoneCode", "dev-phone"),
                token));
        assertThat(bound.get("needPhone")).isEqualTo(false);
        assertThat(bound.get("customerId")).isEqualTo(String.valueOf(walkIn.getId()));
        JwtPrincipal p = jwtService.parse((String) bound.get("token"));
        assertThat(p.subjectId()).isEqualTo(walkIn.getId());
        assertThat(related.bookingCustomerIds()).containsExactly(walkIn.getId());
        assertThat(customers.findById(Long.parseLong(openidOnlyId))).isEmpty();
        assertThat(sessions.findBySubject("CUSTOMER", Long.parseLong(openidOnlyId))).isEmpty();
        assertThat(sessions.findBySubject("CUSTOMER", walkIn.getId()))
                .isNotEmpty()
                .allMatch(s -> s.getSubjectId() == walkIn.getId());
    }

    @Test
    void bindPhoneConflictIs40908() {
        postJson("/api/v1/c/auth/wechat", Map.of("code", "dev-c2", "phoneCode", "dev-phone"), null);
        Map<String, Object> login = data(postJson("/api/v1/c/auth/wechat", Map.of("code", "dev"), null));
        ResponseEntity<Map<String, Object>> res = postJson(
                "/api/v1/c/auth/bind-phone",
                Map.of("phone", "13800138000"),
                (String) login.get("token"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(40908);
        assertThat(related.humanTasks()).isNotEmpty();
        assertThat(related.humanTasks().getFirst().getBizKey().length()).isLessThanOrEqualTo(64);
    }

    @Test
    void bindPhoneRequiresJwt() throws Exception {
        mockMvc.perform(post("/api/v1/c/auth/bind-phone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800138000\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    void staffDevLoginIssuesEightHourJwt() {
        ResponseEntity<Map<String, Object>> res = postJson(
                "/api/v1/staff/auth/wechat", Map.of("code", "dev-staff"), null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = data(res);
        assertThat(data.get("expiresIn")).isEqualTo(28800);
        assertThat(data.get("typ")).isEqualTo("A");
        assertThat(data.get("username")).isEqualTo("demo.admin");
        assertThat(data.get("staffId")).isEqualTo(String.valueOf(DemoStaffIds.ADMIN));
        JwtPrincipal p = jwtService.parse((String) data.get("token"));
        assertThat(p.typ()).isEqualTo(TokenType.A);
        assertThat(p.staffId()).isEqualTo(DemoStaffIds.ADMIN);
        assertThat(p.scopeType()).isEqualTo("ALL");
    }

    @Test
    void unknownCodeIs40001() {
        ResponseEntity<Map<String, Object>> res = postJson("/api/v1/c/auth/wechat", Map.of("code", "nope"), null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(40001);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ResponseEntity<Map<String, Object>> res) {
        assertThat(res.getBody()).isNotNull();
        return (Map<String, Object>) res.getBody().get("data");
    }

    private ResponseEntity<Map<String, Object>> postJson(String path, Map<String, ?> body, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), MAP);
    }
}
