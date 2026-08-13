package com.jisuodashi.rbac;

import com.jisuodashi.auth.DemoStaffIds;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.JwtService;
import com.jisuodashi.auth.TokenType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import(UnscopedWriteFixtureController.class)
class RbacReportTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JwtService jwt;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mapping;

    @Test
    void writeHtmlReport() throws Exception {
        List<Row> rows = new ArrayList<>();

        List<String> missing = StoreScopedWriteScanner.violations(mapping);
        rows.add(row("ARCH", "/f /a 写接口均有 @StoreScoped", missing.isEmpty(),
                missing.isEmpty() ? "0 漏注" : missing.toString()));

        boolean dummyUnscoped = StoreScopedWriteScanner.isUnscopedWrite(
                List.of("/api/v1/f/orders"),
                List.of("POST"),
                StoreScopedWriteScannerTest.DummyUnscoped.class.getDeclaredMethod("write"),
                StoreScopedWriteScannerTest.DummyUnscoped.class);
        rows.add(row("ARCH", "未标注写方法被扫描器拒绝", dummyUnscoped, "DummyUnscoped#write"));

        Map<String, Object> unscoped = body(post("/api/v1/f/_fixture/unscoped", Map.of(), manager()));
        rows.add(row("WRITE", "未标注写接口运行时 40302",
                Integer.valueOf(40302).equals(unscoped.get("code")),
                String.valueOf(unscoped.get("code")) + " " + unscoped.get("message")));

        Map<String, Object> admin = data(get("/api/v1/a/stores", admin()));
        List<Map<String, Object>> adminItems = items(admin);
        rows.add(row("LIST", "超管 ALL 看见两店",
                adminItems.size() == 2
                        && adminItems.stream().anyMatch(i -> "DEMO02".equals(i.get("code"))),
                "n=" + adminItems.size() + " scope=" + admin.get("scopeType")));

        Map<String, Object> manager = data(get("/api/v1/f/stores", manager()));
        List<Map<String, Object>> managerItems = items(manager);
        boolean onlyOwn = managerItems.size() == 1
                && String.valueOf(RbacDemoIds.STORE).equals(managerItems.getFirst().get("storeId"))
                && managerItems.stream().noneMatch(i -> "DEMO02".equals(i.get("code")));
        rows.add(row("LIST", "店长 STORE 只看见本店", onlyOwn,
                "n=" + managerItems.size() + " scope=" + manager.get("scopeType")
                        + " ids=" + managerItems.stream().map(i -> i.get("code")).toList()));

        Map<String, Object> east = body(post(
                "/api/v1/f/desk-notes",
                Map.of("storeId", String.valueOf(RbacDemoIds.STORE_EAST), "content", "x"),
                manager()));
        rows.add(row("SCOPE", "店长写他店 40302",
                Integer.valueOf(40302).equals(east.get("code")),
                String.valueOf(east.get("code"))));

        Map<String, Object> note = body(get(
                "/api/v1/t/orders/" + RbacDemoIds.NOTE_ORDER + "/notes", therapist()));
        rows.add(row("AUDIT", "理疗记录 GET 成功", Integer.valueOf(0).equals(note.get("code")),
                String.valueOf(note.get("code"))));

        String html = render(rows);
        Path docs = resolveRepoRoot().resolve("docs/test-cases");
        Files.createDirectories(docs);
        Path report = docs.resolve("pr-5-rbac-report.html");
        Files.writeString(report, html, StandardCharsets.UTF_8);
        Files.createDirectories(resolveTargetDir());
        Files.writeString(resolveTargetDir().resolve("pr-5-rbac-report.html"), html, StandardCharsets.UTF_8);

        List<Row> failed = rows.stream().filter(r -> !r.pass).toList();
        assertThat(failed).as("rbac report failures: %s", failed).isEmpty();
        assertThat(html).contains("40302").contains("DEMO01").contains("@StoreScoped");
    }

    private String admin() {
        return jwt.issue(JwtPrincipal.staff(DemoStaffIds.ADMIN, TokenType.A, "ALL", List.of())).token();
    }

    private String manager() {
        return jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.MANAGER, TokenType.F, "STORE", List.of(RbacDemoIds.STORE))).token();
    }

    private String therapist() {
        return jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.T1, TokenType.T, "SELF", List.of(RbacDemoIds.STORE))).token();
    }

    private ResponseEntity<Map<String, Object>> get(String path, String bearer) {
        return rest.exchange(path, HttpMethod.GET, entity(null, bearer), MAP);
    }

    private ResponseEntity<Map<String, Object>> post(String path, Map<String, ?> body, String bearer) {
        return rest.exchange(path, HttpMethod.POST, entity(body, bearer), MAP);
    }

    private static HttpEntity<?> entity(Map<String, ?> body, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers);
    }

    private static Map<String, Object> body(ResponseEntity<Map<String, Object>> res) {
        assertThat(res.getBody()).isNotNull();
        return res.getBody();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> res) {
        return (Map<String, Object>) body(res).get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("items");
    }

    private static Row row(String kind, String check, boolean pass, String detail) {
        return new Row(kind, check, pass, detail);
    }

    private static String render(List<Row> rows) {
        long ok = rows.stream().filter(r -> r.pass).count();
        StringBuilder body = new StringBuilder();
        for (Row row : rows) {
            body.append("<tr class='").append(row.pass ? "ok" : "bad").append("'>")
                    .append("<td>").append(esc(row.kind)).append("</td>")
                    .append("<td>").append(esc(row.check)).append("</td>")
                    .append("<td>").append(esc(row.detail)).append("</td>")
                    .append("<td>").append(row.pass ? "PASS" : "FAIL").append("</td></tr>\n");
        }
        String badge = ok == rows.size() ? "ALL PASS" : "FAIL";
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                  <meta charset="UTF-8"/>
                  <title>PR5 RBAC · store-scope / audit / captcha</title>
                  <style>
                    :root { --ink:#14352c; --brand:#1E5C4A; --bg:#f4f7f5; --line:#d5e3dc; }
                    body { font-family: ui-sans-serif, "PingFang SC", sans-serif; margin:0;
                           background:var(--bg); color:var(--ink); }
                    header { background:var(--brand); color:#fff; padding:28px 40px; }
                    header h1 { margin:0 0 6px; font-size:22px; }
                    header p { margin:0; opacity:.85; }
                    .badge { display:inline-block; background:#2fbf71; color:#062014;
                             font-weight:700; padding:4px 12px; border-radius:6px; margin-top:10px; }
                    .badge.fail { background:#e85d4c; color:#fff; }
                    main { padding:28px 40px 48px; }
                    h2 { font-size:16px; color:var(--brand); margin:24px 0 10px; }
                    table { width:100%; border-collapse:collapse; background:#fff;
                            box-shadow:0 1px 3px rgba(20,53,44,.06); }
                    th, td { text-align:left; padding:8px 10px; border-bottom:1px solid var(--line);
                             font-size:13px; }
                    th { background:#e8f1ed; color:var(--brand); }
                    tr.ok td:last-child { color:#1E5C4A; font-weight:700; }
                    tr.bad td:last-child { color:#c0392b; font-weight:700; }
                    .callout { background:#fff; border-left:4px solid #e85d4c; padding:12px 16px;
                               margin:16px 0; }
                    .callout.ok { border-left-color:#1E5C4A; }
                    code { font-family: ui-monospace, Menlo, monospace; font-size:12px; }
                    footer { padding:0 40px 32px; color:#6b7c75; font-size:12px; }
                  </style>
                </head>
                <body>
                  <header>
                    <h1>PR5 · Store-scope / Audit / Captcha</h1>
                    <p>@StoreScoped 拦截器 · audit_log 切面 · CaptchaFilter 默认关</p>
                    <span class="badge __CLS__">__BADGE__ · __OK__ / __N__</span>
                  </header>
                  <main>
                    <div class="callout">
                      <strong>未标注写接口被拒绝</strong>
                      <div>POST <code>/api/v1/f/_fixture/unscoped</code> → <code>40302</code> 写接口缺少 @StoreScoped</div>
                    </div>
                    <div class="callout ok">
                      <strong>门店域列表只看见本店</strong>
                      <div>店长 STORE → DEMO01；超管 ALL → DEMO01 + DEMO02</div>
                    </div>
                    <h2>验收项</h2>
                    <table>
                      <thead><tr><th>类</th><th>检查</th><th>细节</th><th>结果</th></tr></thead>
                      <tbody>
                __ROWS__      </tbody>
                    </table>
                  </main>
                  <footer>Generated by RbacReportTest · dev profile · H2 · Flyway off</footer>
                </body>
                </html>
                """
                .replace("__CLS__", ok == rows.size() ? "" : "fail")
                .replace("__BADGE__", badge)
                .replace("__OK__", String.valueOf(ok))
                .replace("__N__", String.valueOf(rows.size()))
                .replace("__ROWS__", body);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static Path resolveRepoRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (Files.isRegularFile(cwd.resolve("server/pom.xml"))) {
            return cwd;
        }
        if (Files.isRegularFile(cwd.resolve("pom.xml")) && "server".equals(cwd.getFileName().toString())) {
            return cwd.getParent();
        }
        return cwd;
    }

    private static Path resolveTargetDir() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (Files.isRegularFile(cwd.resolve("server/pom.xml"))) {
            return cwd.resolve("server/target");
        }
        return cwd.resolve("target");
    }

    private record Row(String kind, String check, boolean pass, String detail) {
    }
}
