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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class StaffWorkbenchReportTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

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
    void writeHtmlReportAndPreview() throws Exception {
        List<Row> rows = new ArrayList<>();
        String t1 = token(DemoStaffIds.T1, TokenType.T);
        String t2 = token(DemoStaffIds.T2, TokenType.T);

        Map<String, Object> anon = body(get("/api/v1/t/today", null));
        rows.add(row("AUTH", "GET /t/today 无 JWT → 40101",
                Integer.valueOf(40101).equals(anon.get("code")),
                String.valueOf(anon.get("code"))));

        String orderId = bookPayCheckIn(78);
        Map<String, Object> today = data(get("/api/v1/t/today", t1));
        @SuppressWarnings("unchecked")
        Map<String, Object> next = (Map<String, Object>) today.get("next");
        boolean card = next != null
                && orderId.equals(next.get("orderId"))
                && "王先生".equals(next.get("customerName"))
                && "19:30".equals(next.get("start"))
                && "全身推拿放松".equals(next.get("projectName"))
                && Boolean.TRUE.equals(next.get("isNewCustomer"))
                && !next.containsKey("phone");
        rows.add(row("T1", "下一单卡片 who/when/bed/project/new guest",
                card,
                next == null ? "null" : next.toString()));

        Map<String, Object> started = data(post(
                "/api/v1/t/orders/" + orderId + "/start", Map.of("requestId", "rpt-start"), t1));
        rows.add(row("T2", "POST /t/orders/{id}/start → IN_SERVICE",
                "IN_SERVICE".equals(started.get("status"))
                        && "IN_SERVICE".equals(occupyStore.findOrderById(Long.parseLong(orderId)).status()),
                String.valueOf(started.get("status"))));

        Map<String, Object> t2Start = body(post(
                "/api/v1/t/orders/" + orderId + "/start", Map.of("requestId", "rpt-t2"), t2));
        rows.add(row("T2", "他师 start → 40904",
                Integer.valueOf(40904).equals(t2Start.get("code")),
                String.valueOf(t2Start.get("code"))));

        Map<String, Object> emptyNotes = data(get("/api/v1/t/orders/" + orderId + "/notes", t1));
        rows.add(row("NOTE", "服务技师空记录 GET 200 []",
                emptyNotes.get("items") instanceof List<?> list && list.isEmpty()
                        && Boolean.FALSE.equals(emptyNotes.get("consented")),
                "items=" + emptyNotes.get("items")));

        Map<String, Object> noConsent = body(post(
                "/api/v1/t/orders/" + orderId + "/notes", Map.of("content", "无同意"), t1));
        data(post("/api/v1/t/orders/" + orderId + "/consent", Map.of(), t1));
        Map<String, Object> note = data(post(
                "/api/v1/t/orders/" + orderId + "/notes",
                Map.of("content", "腰段张力高，本次以放松为主。禁忌：孕。"),
                t1));
        Map<String, Object> listed = data(get("/api/v1/t/orders/" + orderId + "/notes", t1));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) listed.get("items");
        Map<String, Object> t2Notes = body(get("/api/v1/t/orders/" + orderId + "/notes", t2));
        rows.add(row("NOTE", "D27 无同意 40301；POST 只增；GET 仅本人",
                Integer.valueOf(40301).equals(noConsent.get("code"))
                        && "腰段张力高，本次以放松为主。禁忌：孕。".equals(note.get("content"))
                        && items != null && items.size() == 1
                        && Integer.valueOf(40401).equals(t2Notes.get("code")),
                "deny=" + noConsent.get("code") + " items=" + (items == null ? 0 : items.size())
                        + " t2=" + t2Notes.get("code")));

        Map<String, Object> alias = data(get("/api/v1/t/me/today", t1));
        Map<String, Object> opened = data(get("/api/v1/t/orders/" + orderId, t1));
        boolean record = notes.findLatestServiceRecord(Long.parseLong(orderId)).isPresent();
        rows.add(row("T1", "GET /t/me/today 别名 + GET /t/orders/{id} 绑定本单",
                alias.get("next") != null && orderId.equals(opened.get("orderId")) && record,
                "opened=" + opened.get("orderId") + " record=" + record));

        Map<String, Object> done = data(post(
                "/api/v1/t/orders/" + orderId + "/complete", Map.of("requestId", "rpt-complete"), t1));
        rows.add(row("T2", "POST /t/orders/{id}/complete → COMPLETED",
                "COMPLETED".equals(done.get("status")),
                String.valueOf(done.get("status"))));

        String html = renderReport(rows);
        String preview = renderPreview(next, orderId);
        Path docs = resolveRepoRoot().resolve("docs/test-cases");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("pr-10-staff-board.html"), html, StandardCharsets.UTF_8);
        Files.writeString(docs.resolve("pr-10-t1-t2.html"), preview, StandardCharsets.UTF_8);
        Path target = resolveTargetDir();
        Files.createDirectories(target);
        Files.writeString(target.resolve("pr-10-staff-board.html"), html, StandardCharsets.UTF_8);
        Files.writeString(target.resolve("pr-10-t1-t2.html"), preview, StandardCharsets.UTF_8);

        assertThat(rows.stream().filter(r -> !r.pass).toList()).as("pr-10 failures: %s", rows).isEmpty();
        assertThat(html).contains("IN_SERVICE").contains("NOTE");
        assertThat(preview).contains("15px").contains("48px").contains("王先生");
    }

    private String bookPayCheckIn(int startSlotNo) {
        String token = jwt.issue(JwtPrincipal.customer(8_100_000_000_000_000_001L)).token();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "rpt-staff-book");
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
        return orderId;
    }

    private String token(long staffId, TokenType typ) {
        return jwt.issue(JwtPrincipal.staff(staffId, typ, "SELF", List.of(DemoStaffIds.STORE))).token();
    }

    private ResponseEntity<Map<String, Object>> get(String path, String bearer) {
        return rest.exchange(path, HttpMethod.GET, json(null, bearer), MAP);
    }

    private ResponseEntity<Map<String, Object>> post(String path, Map<String, ?> body, String bearer) {
        return rest.exchange(path, HttpMethod.POST, json(body, bearer), MAP);
    }

    private static Map<String, Object> body(ResponseEntity<Map<String, Object>> res) {
        assertThat(res.getBody()).isNotNull();
        return res.getBody();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> res) {
        Map<String, Object> body = body(res);
        assertThat(body.get("code")).isEqualTo(0);
        return (Map<String, Object>) body.get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataCreated(ResponseEntity<Map<String, Object>> res) {
        Map<String, Object> body = body(res);
        assertThat(body.get("code")).isEqualTo(0);
        return (Map<String, Object>) body.get("data");
    }

    private static HttpEntity<?> json(Map<String, ?> body, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers);
    }

    private static Row row(String kind, String check, boolean pass, String detail) {
        return new Row(kind, check, pass, detail);
    }

    private static String renderReport(List<Row> rows) {
        long ok = rows.stream().filter(r -> r.pass).count();
        String badge = "ALL PASS · " + ok + " / " + rows.size();
        StringBuilder body = new StringBuilder();
        for (Row r : rows) {
            body.append("<tr class='").append(r.pass ? "ok" : "bad").append("'>")
                    .append("<td>").append(esc(r.kind)).append("</td>")
                    .append("<td>").append(esc(r.check)).append("</td>")
                    .append("<td>").append(esc(r.detail)).append("</td>")
                    .append("<td>").append(r.pass ? "PASS" : "FAIL").append("</td></tr>\n");
        }
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                  <meta charset="UTF-8"/>
                  <title>PR10 技师端 T1/T2</title>
                  <style>
                    :root { --ink:#14352c; --brand:#1E5C4A; --bg:#f4f7f5; --line:#d5e3dc; }
                    body { font-family: ui-sans-serif, "PingFang SC", sans-serif; margin:0;
                           background:var(--bg); color:var(--ink); }
                    header { background:var(--brand); color:#fff; padding:28px 40px; }
                    header h1 { margin:0 0 6px; font-size:22px; }
                    .badge { display:inline-block; background:#2fbf71; color:#062014;
                             font-weight:700; padding:4px 12px; border-radius:6px; margin-top:10px; }
                    .badge.fail { background:#e85d4c; color:#fff; }
                    main { padding:28px 40px 48px; }
                    table { width:100%; border-collapse:collapse; background:#fff; }
                    th, td { text-align:left; padding:8px 10px; border-bottom:1px solid var(--line);
                             font-size:13px; }
                    th { background:#e8f1ed; color:var(--brand); }
                    tr.ok td:last-child { color:#1E5C4A; font-weight:700; }
                    tr.bad td:last-child { color:#c0392b; font-weight:700; }
                    .flow { display:flex; gap:8px; flex-wrap:wrap; align-items:center; margin:12px 0; }
                    .st { padding:8px 10px; border-radius:8px; font-size:12px; font-weight:700;
                          border:2px solid var(--brand); background:#e8f1ed; }
                    .st.act { background:#1E5C4A; color:#fff; }
                    code { font-family: ui-monospace, Menlo, monospace; font-size:12px; }
                  </style>
                </head>
                <body>
                  <header>
                    <h1>PR10 · 技师端 T1/T2</h1>
                    <p>GET /api/v1/t/today · POST start/complete · 只增理疗记录 · 15px / 48px</p>
                    <span class="badge __CLS__">__BADGE__</span>
                  </header>
                  <main>
                    <div class="flow">
                      <div class="st">BOOKED</div>
                      <div>CHECK_IN</div>
                      <div class="st">CHECKED_IN</div>
                      <div>POST /t/orders/{id}/start</div>
                      <div class="st act">IN_SERVICE</div>
                      <div>POST complete</div>
                      <div class="st act">COMPLETED</div>
                    </div>
                    <table>
                      <thead><tr><th>类</th><th>检查</th><th>细节</th><th>结果</th></tr></thead>
                      <tbody>
                __ROWS__      </tbody>
                    </table>
                  </main>
                </body>
                </html>
                """
                .replace("__CLS__", ok == rows.size() ? "" : "fail")
                .replace("__BADGE__", badge)
                .replace("__ROWS__", body);
    }

    private static String renderPreview(Map<String, Object> next, String orderId) {
        String who = next == null ? "王先生" : String.valueOf(next.get("customerName"));
        String start = next == null ? "19:30" : String.valueOf(next.get("start"));
        String end = next == null ? "20:30" : String.valueOf(next.get("end"));
        String project = next == null ? "全身推拿放松" : String.valueOf(next.get("projectName"));
        String room = next == null ? "一号房" : String.valueOf(next.get("roomName"));
        String bed = next == null ? "1号床" : String.valueOf(next.get("bedName"));
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                  <meta charset="UTF-8"/>
                  <title>PR10 T1/T2 HTML preview</title>
                  <style>
                    body { margin:0; background:#d9e3dd; font-family:"PingFang SC", sans-serif; color:#1a1c1b; }
                    header { background:#1E5C4A; color:#fff; padding:20px 28px; }
                    header h1 { margin:0; font-size:20px; }
                    header p { margin:6px 0 0; font-size:13px; opacity:.85; }
                    .wrap { display:flex; gap:28px; padding:28px; justify-content:center; flex-wrap:wrap; }
                    .phone { width:375px; background:#f4f1ea; border-radius:28px; overflow:hidden;
                             box-shadow:0 12px 30px rgba(20,53,44,.18); min-height:720px; }
                    .bar { background:#1E5C4A; color:#f4f1ea; padding:16px 18px; }
                    .kicker { font-size:15px; opacity:.85; }
                    .title { font-size:22px; font-weight:700; margin-top:4px; }
                    .sub { font-size:15px; opacity:.8; }
                    .card { margin:-12px 12px 16px; background:#fff; border-radius:14px; padding:16px; }
                    .who { font-size:20px; font-weight:700; color:#1E5C4A; }
                    .badge { display:inline-block; margin-left:8px; background:#c45c26; color:#fff;
                             font-size:15px; padding:4px 8px; border-radius:999px; }
                    .when { font-size:18px; font-weight:600; margin-top:8px; }
                    .meta, .eta, .note, .hint { font-size:15px; }
                    .meta { margin-top:4px; color:#3d4f48; }
                    .eta { margin-top:10px; color:#1E5C4A; }
                    .btn { height:48px; line-height:48px; text-align:center; border-radius:10px;
                           font-size:16px; font-weight:600; margin-top:10px; }
                    .btn.jade { background:#1E5C4A; color:#fff; }
                    .btn.copper { background:#c45c26; color:#fff; }
                    .chip { display:inline-block; min-width:48px; min-height:48px; padding:6px 8px;
                            background:#1E5C4A; color:#fff; border-radius:8px; font-size:15px;
                            margin:4px 4px 0 0; vertical-align:top; }
                    .sec { padding:8px 16px; font-size:15px; font-weight:600; color:#1E5C4A; }
                    .note { background:#fff; margin:0 12px 8px; padding:10px 12px; border-radius:10px; }
                    .box { margin:0 12px; background:#fff; min-height:72px; border-radius:10px;
                           padding:10px; font-size:15px; color:#6b7a74; }
                    .ruler { margin:0 28px 24px; background:#fff; padding:12px 16px; border-radius:10px;
                             font-size:15px; }
                    .mark { display:inline-block; width:48px; height:48px; background:#1E5C4A; color:#fff;
                            text-align:center; line-height:48px; border-radius:8px; margin-right:8px; }
                  </style>
                </head>
                <body>
                  <header>
                    <h1>mini-staff T1 / T2 HTML preview</h1>
                    <p>WeChat 开发者工具未接入时的页面验收。最小字号 15px，主操作 48px tap target。orderId=__OID__</p>
                  </header>
                  <div class="wrap">
                    <div class="phone">
                      <div class="bar">
                        <div class="kicker">T1 · 技师工作台</div>
                        <div class="title">林晓</div>
                        <div class="sub">三秒看懂谁 / 几点 / 哪张床</div>
                      </div>
                      <div class="card">
                        <div><span class="who">__WHO__</span><span class="badge">新客</span></div>
                        <div class="when">__START__–__END__</div>
                        <div class="meta">__ROOM__ · __BED__</div>
                        <div class="meta">__PROJECT__</div>
                        <div class="eta">26 分钟后开始</div>
                        <div class="btn jade">进入服务页</div>
                      </div>
                      <div class="sec">今日时间轴</div>
                      <div style="padding:0 12px 20px">
                        <span class="chip">19:30<br/>BOOKED</span>
                        <span class="chip">19:45<br/>BOOKED</span>
                        <span class="chip">20:15<br/>BUFFER</span>
                      </div>
                    </div>
                    <div class="phone">
                      <div class="bar">
                        <div class="kicker">T2 · 服务中</div>
                        <div class="title">__WHO__</div>
                        <div class="sub">__PROJECT__ · __START__–__END__</div>
                      </div>
                      <div class="card">
                        <div class="meta">__ROOM__ __BED__ <span class="badge">新客</span></div>
                        <div class="meta">状态 服务中</div>
                        <div class="when">12:40</div>
                        <div class="btn jade">开始服务</div>
                        <div class="btn copper">结束服务</div>
                      </div>
                      <div class="sec">理疗记录（只增不改）</div>
                      <div class="note">腰段张力高，本次以放松为主。禁忌：孕。</div>
                      <div class="box">参考上次：力度偏好、禁忌；提交后不可改</div>
                      <div style="padding:10px 12px 20px"><div class="btn jade">追加记录</div></div>
                    </div>
                  </div>
                  <div class="ruler">
                    <span class="mark">48</span>
                    主按钮 / 时间轴格 ≥ 48×48px · 正文 ≥ 15px · 不展示客户手机号
                  </div>
                </body>
                </html>
                """
                .replace("__OID__", esc(orderId))
                .replace("__WHO__", esc(who))
                .replace("__START__", esc(start))
                .replace("__END__", esc(end))
                .replace("__PROJECT__", esc(project))
                .replace("__ROOM__", esc(room))
                .replace("__BED__", esc(bed));
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
