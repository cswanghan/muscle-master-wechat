package com.jisuodashi.employment;

import com.jisuodashi.DevApiTest;
import com.jisuodashi.auth.DemoStaffIds;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.JwtService;
import com.jisuodashi.auth.TokenType;
import com.jisuodashi.catalog.CatalogRepository;
import com.jisuodashi.catalog.DemoCatalogIds;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DevApiTest
class EmploymentApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {
            };

    /** 同一个 JVM 里多个用例都在建人，用户名得各不相同。 */
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JwtService jwt;

    @Autowired
    private CatalogRepository catalog;

    @Test
    void rosterListsSeededStaff() {
        Map<String, Object> data = data(call("/api/v1/a/employment/staff", HttpMethod.GET, null));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        assertThat(items).isNotEmpty();
        assertThat(items).anySatisfy(i -> assertThat(i.get("username")).isEqualTo("demo.admin"));
        // 技师行要挂上 therapistId，否则前端没法从花名册跳到排班。
        assertThat(items).anySatisfy(i -> assertThat(i.get("therapistId")).isNotNull());
    }

    @Test
    void onboardTherapistCreatesBookableTherapist() {
        String username = "hire." + SEQ.incrementAndGet();
        Map<String, Object> data = data(call("/api/v1/a/employment/onboard", HttpMethod.POST, Map.of(
                "username", username,
                "name", "新来的技师",
                "roleCodes", List.of("THERAPIST"),
                "storeIds", List.of(String.valueOf(DemoCatalogIds.STORE)))));
        String therapistId = (String) data.get("therapistId");
        assertThat(therapistId).isNotNull();

        // 建了档还得能接活：没有项目就永远排不出可约时段。
        assertThat(catalog.findTherapist(Long.parseLong(therapistId)))
                .hasValueSatisfying(t -> {
                    assertThat(t.status()).isEqualTo(1);
                    assertThat(t.homeStoreId()).isEqualTo(DemoCatalogIds.STORE);
                    assertThat(t.projectIds()).isNotEmpty();
                });
    }

    @Test
    void onboardRejectsPrivilegeEscalation() {
        ResponseEntity<Map<String, Object>> res = call("/api/v1/a/employment/onboard", HttpMethod.POST, Map.of(
                "username", "escalate." + SEQ.incrementAndGet(),
                "name", "想当超管的人",
                "roleCodes", List.of("SUPER_ADMIN"),
                "storeIds", List.of(String.valueOf(DemoCatalogIds.STORE))));
        assertThat(res.getBody()).isNotNull();
        assertThat((Integer) res.getBody().get("code")).isNotZero();
    }

    @Test
    void onboardRejectsDuplicateUsername() {
        ResponseEntity<Map<String, Object>> res = call("/api/v1/a/employment/onboard", HttpMethod.POST, Map.of(
                "username", "demo.front",
                "name", "重名",
                "roleCodes", List.of("FRONTDESK"),
                "storeIds", List.of(String.valueOf(DemoCatalogIds.STORE))));
        assertThat((Integer) res.getBody().get("code")).isNotZero();
    }

    @Test
    void offboardThenRehireFlipsStatusAndLeavesTrail() {
        String username = "leaver." + SEQ.incrementAndGet();
        Map<String, Object> hired = data(call("/api/v1/a/employment/onboard", HttpMethod.POST, Map.of(
                "username", username,
                "name", "要离职的技师",
                "roleCodes", List.of("THERAPIST"),
                "storeIds", List.of(String.valueOf(DemoCatalogIds.STORE)))));
        String staffId = (String) hired.get("staffId");
        long therapistId = Long.parseLong((String) hired.get("therapistId"));

        // 新人名下没单，离职不该被挡。
        Map<String, Object> gone = data(call(
                "/api/v1/a/employment/" + staffId + "/offboard", HttpMethod.POST,
                Map.of("reason", "个人原因")));
        assertThat((Integer) gone.get("status")).isZero();
        assertThat((List<?>) gone.get("blockedOrders")).isEmpty();
        assertThat(catalog.findTherapist(therapistId)).hasValueSatisfying(t ->
                assertThat(t.status()).isZero());

        // 离职的人还要查得到，只是不在职——所以是停用不是删除。
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> left = (List<Map<String, Object>>)
                data(call("/api/v1/a/employment/staff?status=0", HttpMethod.GET, null)).get("items");
        assertThat(left).anySatisfy(i -> assertThat(i.get("staffId")).isEqualTo(staffId));

        Map<String, Object> back = data(call(
                "/api/v1/a/employment/" + staffId + "/rehire", HttpMethod.POST, Map.of()));
        assertThat((Integer) back.get("status")).isEqualTo(1);
        assertThat(catalog.findTherapist(therapistId)).hasValueSatisfying(t ->
                assertThat(t.status()).isEqualTo(1));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> logs = (List<Map<String, Object>>)
                data(call("/api/v1/a/employment/logs?staffId=" + staffId, HttpMethod.GET, null)).get("items");
        assertThat(logs).extracting(l -> l.get("action"))
                .containsExactly("REHIRE", "OFFBOARD", "ONBOARD");
    }

    @Test
    void offboardTwiceIsRejected() {
        String username = "twice." + SEQ.incrementAndGet();
        String staffId = (String) data(call("/api/v1/a/employment/onboard", HttpMethod.POST, Map.of(
                "username", username,
                "name", "离两次",
                "roleCodes", List.of("FRONTDESK"),
                "storeIds", List.of(String.valueOf(DemoCatalogIds.STORE))))).get("staffId");

        call("/api/v1/a/employment/" + staffId + "/offboard", HttpMethod.POST, Map.of());
        ResponseEntity<Map<String, Object>> again =
                call("/api/v1/a/employment/" + staffId + "/offboard", HttpMethod.POST, Map.of());
        assertThat((Integer) again.getBody().get("code")).isNotZero();
    }

    @Test
    void offboardIsBlockedWhileTherapistStillHasLiveOrders() {
        // 种子技师身上有演示订单；被挡时状态不变，冲突单要一并返回。
        Map<String, Object> roster = data(call("/api/v1/a/employment/staff?status=1", HttpMethod.GET, null));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) roster.get("items");
        Map<String, Object> busy = items.stream()
                .filter(i -> i.get("therapistId") != null && (Integer) i.get("pendingOrders") > 0)
                .findFirst()
                .orElse(null);
        if (busy == null) {
            return;
        }
        Map<String, Object> res = data(call(
                "/api/v1/a/employment/" + busy.get("staffId") + "/offboard", HttpMethod.POST, Map.of()));
        assertThat((Integer) res.get("status")).isEqualTo(1);
        assertThat((List<?>) res.get("blockedOrders")).isNotEmpty();

        // force 是给"已经改约/退款完了"的情况留的出口，不是默认行为。
        Map<String, Object> forced = data(call(
                "/api/v1/a/employment/" + busy.get("staffId") + "/offboard", HttpMethod.POST,
                Map.of("force", true, "reason", "已转单")));
        assertThat((Integer) forced.get("status")).isZero();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ResponseEntity<Map<String, Object>> res) {
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(0);
        return (Map<String, Object>) res.getBody().get("data");
    }

    private ResponseEntity<Map<String, Object>> call(String path, HttpMethod method, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(jwt.issue(
                JwtPrincipal.staff(DemoStaffIds.ADMIN, TokenType.A, "ALL", List.of())).token());
        return rest.exchange(path, method, new HttpEntity<>(body, h), MAP);
    }
}
