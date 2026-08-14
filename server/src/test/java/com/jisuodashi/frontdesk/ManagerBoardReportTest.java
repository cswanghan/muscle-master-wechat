package com.jisuodashi.frontdesk;

import com.jisuodashi.auth.CustomerRepository;
import com.jisuodashi.auth.DemoStaffIds;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.JwtService;
import com.jisuodashi.auth.TokenType;
import com.jisuodashi.catalog.DemoCatalogIds;
import com.jisuodashi.inventory.InMemoryScheduleExceptionStore;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.inventory.ScheduleExceptionService;
import com.jisuodashi.inventory.ScheduleExceptionStore;
import com.jisuodashi.order.FireContext;
import com.jisuodashi.order.OrderEvent;
import com.jisuodashi.order.OrderStateMachine;
import com.jisuodashi.payment.InMemoryPaymentStore;
import com.jisuodashi.payment.MockWeChatPayClient;
import com.jisuodashi.payment.Payment;
import com.jisuodashi.payment.PaymentService;
import com.jisuodashi.payment.WeChatPayClient;
import com.jisuodashi.workflow.WorkflowInstance;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §1 店长 M1：完整满班率（全日 + byHour）+ 待办（请假 / ≥¥500 退款 / 异常单 / 人工队列）。
 * 走真实 HTTP，并顺带把 mini-staff 店长页的 15px / 48px 与语义化 key 做静态门禁。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class ManagerBoardReportTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    private static final String TODAY = "2026-08-14";
    /** demo 日历种到 +14 天；这天没有 fixture 订单，请假不会撞上已售时段。 */
    private static final String LEAVE_DATE = "2026-08-16";
    private static final Pattern FONT_SIZE = Pattern.compile("font-size:\\s*(\\d+)rpx");
    private static final Pattern GROUP_KEY = Pattern.compile("\\{\\s*key:\\s*'([^']+)'");

    /** 页面上的四个待办分组，key 必须语义化——顺序即页面自上而下的优先级。 */
    private static final List<String> GROUP_KEYS = List.of("leave", "refund", "abnormal", "queue");

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JwtService jwt;
    @Autowired
    private InMemorySlotOccupyStore occupyStore;
    @Autowired
    private InMemoryPaymentStore payments;
    @Autowired
    private InMemoryScheduleExceptionStore exceptions;
    @Autowired
    private CustomerRepository customers;
    @Autowired
    private OrderStateMachine machine;
    @Autowired
    private WeChatPayClient wechat;

    @BeforeEach
    void reset() {
        occupyStore.resetDemoCalendar();
        payments.clear();
        exceptions.clear();
        customers.clear();
        if (wechat instanceof MockWeChatPayClient mock) {
            mock.resetRefunds();
            mock.failRefunds = false;
        }
    }

    @Test
    void writeHtmlReportAndPreview() throws Exception {
        List<Row> rows = new ArrayList<>();
        String manager = managerToken();

        // ---- 门禁 ----
        Map<String, Object> anon = body(rest.exchange(
                "/api/v1/f/metrics/utilization", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), MAP));
        rows.add(row("AUTH", "GET /f/metrics/utilization 无 JWT → 40101",
                Integer.valueOf(40101).equals(anon.get("code")), String.valueOf(anon.get("code"))));

        ResponseEntity<Map<String, Object>> therapist = get("/api/v1/f/metrics/utilization", therapistToken());
        rows.add(row("AUTH", "技师 token 无 order:list → 403",
                therapist.getStatusCode() == HttpStatus.FORBIDDEN,
                therapist.getStatusCode() + " code=" + body(therapist).get("code")));

        // ---- 满班率 ----
        Map<String, Object> before = data(get("/api/v1/f/metrics/utilization", manager), HttpStatus.OK);
        int rateBefore = intOf(before.get("rateX10000"));
        List<Map<String, Object>> hoursBefore = items(before, "byHour");
        rows.add(row("M1", "全日满班率默认取今天，byHour 覆盖营业小时",
                TODAY.equals(before.get("date")) && hoursBefore.size() >= 12
                        && hoursBefore.stream().allMatch(h -> h.containsKey("hour") && h.containsKey("rateX10000")),
                "date=" + before.get("date") + " hours=" + hoursBefore.size() + " rate=" + rateBefore));

        // ---- 三类待办 ----
        String leaveExceptionId = String.valueOf(data(post("/api/v1/a/schedule-exceptions",
                leave("m1-lv"), adminToken()), HttpStatus.CREATED).get("id"));
        long abnormalOrderId = abortedWalkIn("m1-ab", 44);
        long refundOrderId = refundWaitingApproval("m1-rf");

        Map<String, Object> after = data(get("/api/v1/f/metrics/utilization", manager), HttpStatus.OK);
        int rateAfter = intOf(after.get("rateX10000"));
        rows.add(row("M1", "落单后满班率上升（分母不含 REST）",
                rateAfter > rateBefore,
                rateBefore + " → " + rateAfter));

        int peak = items(after, "byHour").stream().mapToInt(h -> intOf(h.get("rateX10000"))).max().orElse(0);
        rows.add(row("M1", "分时满班率有峰值，可定位忙闲",
                peak > rateAfter, "peak=" + peak + " fullDay=" + rateAfter));

        // ---- 队列 + 客户端分组 ----
        List<Map<String, Object>> open = items(
                data(get("/api/v1/f/human-tasks?status=OPEN", manager), HttpStatus.OK), "items");
        Map<String, List<Map<String, Object>>> grouped = groupTasks(open);
        rows.add(row("QUEUE", "OPEN 队列同时含请假 / 退款 / 异常单",
                grouped.get("leave").size() == 1 && grouped.get("refund").size() == 1
                        && grouped.get("abnormal").size() == 1,
                "leave=" + grouped.get("leave").size() + " refund=" + grouped.get("refund").size()
                        + " abnormal=" + grouped.get("abnormal").size()
                        + " queue=" + grouped.get("queue").size()));

        String leaveTaskId = String.valueOf(grouped.get("leave").getFirst().get("id"));
        String abnormalTaskId = String.valueOf(grouped.get("abnormal").getFirst().get("id"));
        String refundTaskId = String.valueOf(grouped.get("refund").getFirst().get("id"));
        rows.add(row("QUEUE", "未知类型兜底进人工队列，不丢单",
                groupOf("SOMETHING_NEW").equals("queue")
                        && (ScheduleExceptionService.BIZ_KEY_PREFIX + leaveExceptionId)
                        .equals(grouped.get("leave").getFirst().get("bizKey")),
                "bizKey=" + grouped.get("leave").getFirst().get("bizKey")));

        ResponseEntity<Map<String, Object>> frontApprove = post(
                "/api/v1/f/human-tasks/" + leaveTaskId + "/approve",
                Map.of("requestId", "m1-lv-front"), frontToken());
        rows.add(row("RBAC", "前台无 schedule:approve → 40301",
                frontApprove.getStatusCode() == HttpStatus.FORBIDDEN
                        && Integer.valueOf(40301).equals(body(frontApprove).get("code")),
                String.valueOf(body(frontApprove).get("code"))));

        // ---- 出度 ----
        Map<String, Object> approved = data(post("/api/v1/f/human-tasks/" + leaveTaskId + "/approve",
                Map.of("requestId", "m1-lv-ap"), manager), HttpStatus.OK);
        rows.add(row("M1", "通过请假 → FREE 翻 REST，任务关闭",
                ScheduleExceptionStore.STATUS_APPROVED.equals(approved.get("status"))
                        && Integer.valueOf(8).equals(approved.get("restSlots")),
                "status=" + approved.get("status") + " restSlots=" + approved.get("restSlots")));

        Map<String, Object> resolved = data(post("/api/v1/f/human-tasks/" + abnormalTaskId + "/resolve",
                resolve("m1-ab-rs", FrontDeskService.ACTION_RESOLVE_COMPLETE, "已补做完成"), manager), HttpStatus.OK);
        rows.add(row("M1", "异常单按完成结单 → COMPLETED + DONE",
                "COMPLETED".equals(resolved.get("orderStatus")) && "DONE".equals(resolved.get("taskStatus"))
                        && "COMPLETED".equals(occupyStore.findOrderById(abnormalOrderId).status()),
                "order=" + resolved.get("orderStatus") + " task=" + resolved.get("taskStatus")));

        Map<String, Object> refundApproved = data(post("/api/v1/f/human-tasks/" + refundTaskId + "/approve",
                Map.of("requestId", "m1-rf-ap"), manager), HttpStatus.OK);
        rows.add(row("M1", "≥¥500 退款审批通过 → 微信退款真调用",
                WorkflowInstance.SUCCESS.equals(refundApproved.get("workflowStatus"))
                        && !((MockWeChatPayClient) wechat).refundCalls().isEmpty(),
                "wf=" + refundApproved.get("workflowStatus")
                        + " refundCalls=" + ((MockWeChatPayClient) wechat).refundCalls().size()));

        List<Map<String, Object>> left = items(
                data(get("/api/v1/f/human-tasks?status=OPEN", manager), HttpStatus.OK), "items");
        rows.add(row("QUEUE", "处理完队列清空，店长台回到 0 待办",
                left.isEmpty(), "open=" + left.size() + " refundOrder=" + refundOrderId));

        // ---- mini-staff 静态门禁 ----
        Path page = resolveRepoRoot().resolve("apps/mini-staff/pages/manager");
        String wxss = Files.readString(page.resolve("manager.wxss"), StandardCharsets.UTF_8);
        String js = Files.readString(page.resolve("manager.js"), StandardCharsets.UTF_8);
        String wxml = Files.readString(page.resolve("manager.wxml"), StandardCharsets.UTF_8);

        int minFont = minFontRpx(wxss);
        rows.add(row("A11Y", "正文最小字号 ≥ 30rpx(15px)",
                minFont >= 30, "min=" + minFont + "rpx"));
        rows.add(row("A11Y", "主操作 ≥ 96rpx(48px) tap target",
                wxss.contains("height: 96rpx") && wxss.contains("min-width: 96rpx")
                        && wxss.contains("min-height: 96rpx"),
                "btn 96rpx / tag 96rpx"));

        List<String> keys = groupKeys(js);
        rows.add(row("A11Y", "分组 key 语义化，无数字编号",
                keys.equals(GROUP_KEYS) && keys.stream().allMatch(k -> k.matches("[a-z][a-zA-Z]*")),
                String.join(" / ", keys)));
        rows.add(row("A11Y", "wxml 按 key/label 渲染，不按下标取组",
                wxml.contains("wx:key=\"key\"") && wxml.contains("{{item.label}}")
                        && !wxml.contains("groups[0]"),
                "wx:for groups → item.key"));

        String html = renderReport(rows, rateAfter, peak);
        String preview = renderPreview(rateAfter, items(after, "byHour"), open);
        Path docs = resolveRepoRoot().resolve("docs/test-cases");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("pr-21-manager-board.html"), html, StandardCharsets.UTF_8);
        Files.writeString(docs.resolve("pr-21-manager-m1.html"), preview, StandardCharsets.UTF_8);
        Path target = resolveTargetDir();
        Files.createDirectories(target);
        Files.writeString(target.resolve("pr-21-manager-board.html"), html, StandardCharsets.UTF_8);
        Files.writeString(target.resolve("pr-21-manager-m1.html"), preview, StandardCharsets.UTF_8);

        assertThat(rows.stream().filter(r -> !r.pass).toList()).as("pr-21 failures: %s", rows).isEmpty();
        assertThat(html).contains("LEAVE_APPROVE").contains("满班率");
        assertThat(preview).contains("15px").contains("48px").contains("全日满班率");
    }

    // ------------------------------------------------------------------ fixtures

    /** 前台散客 → START_SERVICE → 中止，落一条 ORDER_ABNORMAL。 */
    private long abortedWalkIn(String requestId, int startSlotNo) {
        Map<String, Object> walkIn = new LinkedHashMap<>();
        walkIn.put("requestId", requestId);
        walkIn.put("phone", "18600009999");
        walkIn.put("customerName", "异常单客");
        walkIn.put("therapistId", String.valueOf(DemoCatalogIds.THERAPIST_LIN));
        walkIn.put("projectId", String.valueOf(DemoCatalogIds.PROJECT_P60));
        walkIn.put("date", TODAY);
        walkIn.put("startSlotNo", startSlotNo);
        walkIn.put("alreadyInStore", true);
        walkIn.put("payChannel", "CASH");
        long orderId = Long.parseLong(String.valueOf(
                data(post("/api/v1/f/walk-ins", walkIn, frontToken()), HttpStatus.CREATED).get("orderId")));
        machine.fire(orderId, OrderEvent.START_SERVICE, FireContext.system());
        Map<String, Object> abort = new LinkedHashMap<>();
        abort.put("requestId", requestId + "-abort");
        abort.put("reason", "客人身体不适中止");
        data(post("/api/v1/f/orders/" + orderId + "/abort", abort, frontToken()), HttpStatus.OK);
        return orderId;
    }

    /** demo 项目最贵 ¥268，靠一笔加钟支付把可退金额抬到 ≥¥500 才会走审批。 */
    private long refundWaitingApproval(String requestId) {
        String customer = jwt.issue(JwtPrincipal.customer(8_100_000_000_000_000_001L)).token();
        Map<String, Object> booking = new LinkedHashMap<>();
        booking.put("requestId", requestId);
        booking.put("storeId", String.valueOf(DemoCatalogIds.STORE));
        booking.put("therapistId", String.valueOf(DemoCatalogIds.THERAPIST_LIN));
        booking.put("projectId", String.valueOf(DemoCatalogIds.PROJECT_P60));
        booking.put("date", TODAY);
        booking.put("startSlotNo", 68);
        long orderId = Long.parseLong(String.valueOf(
                data(post("/api/v1/c/bookings", booking, customer), HttpStatus.CREATED).get("orderId")));
        Map<String, Object> pay = data(post("/api/v1/c/bookings/" + orderId + "/pay",
                Map.of("requestId", "pay-" + requestId), customer), HttpStatus.OK);
        notifyPaid(String.valueOf(pay.get("paymentNo")), 19800);
        insertAddOn(orderId, 40_000L);

        Map<String, Object> refundBody = new LinkedHashMap<>();
        refundBody.put("requestId", requestId + "-refund");
        refundBody.put("amountFen", 59800);
        refundBody.put("reason", "客户改期无法改约");
        Map<String, Object> refunded = data(post("/api/v1/f/orders/" + orderId + "/refund",
                refundBody, frontToken()), HttpStatus.OK);
        assertThat(refunded.get("workflowStatus")).isEqualTo(WorkflowInstance.WAIT_APPROVAL);
        return orderId;
    }

    private void insertAddOn(long orderId, long amountFen) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 14, 19, 0);
        long id = 88_200_000_000_000_000L + amountFen;
        payments.beginWork();
        payments.insert(new Payment(
                id, "P" + id, orderId, Payment.CHANNEL_WECHAT, amountFen, Payment.SUCCESS,
                "prepay-m1-add", "txn-m1-add", now, null, now.plusHours(2), now, now));
        payments.commitWork();
    }

    private void notifyPaid(String paymentNo, long amountFen) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("out_trade_no", paymentNo);
        m.put("transaction_id", "wx_" + paymentNo);
        m.put("amount_fen", amountFen);
        rest.exchange("/api/v1/pay/wechat/notify", HttpMethod.POST,
                new HttpEntity<>(m, jsonHeaders(null)), MAP);
    }

    private static Map<String, Object> leave(String requestId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", requestId);
        m.put("therapistId", String.valueOf(DemoCatalogIds.THERAPIST_LIN));
        m.put("storeId", String.valueOf(DemoCatalogIds.STORE));
        m.put("date", LEAVE_DATE);
        m.put("type", ScheduleExceptionStore.TYPE_LEAVE);
        m.put("startTime", "14:00");
        m.put("endTime", "16:00");
        m.put("reason", "家中有事");
        return m;
    }

    private static Map<String, Object> resolve(String requestId, String action, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", requestId);
        m.put("action", action);
        m.put("note", note);
        return m;
    }

    // ------------------------------------------------------------------ 与 manager.js 同构的分组

    private static String groupOf(String taskType) {
        if (ScheduleExceptionService.TASK_LEAVE_APPROVE.equals(taskType)) {
            return "leave";
        }
        if (PaymentService.TASK_REFUND_APPROVE.equals(taskType)) {
            return "refund";
        }
        if (FrontDeskService.TASK_ORDER_ABNORMAL.equals(taskType)) {
            return "abnormal";
        }
        return "queue";
    }

    private static Map<String, List<Map<String, Object>>> groupTasks(List<Map<String, Object>> tasks) {
        Map<String, List<Map<String, Object>>> bucket = new LinkedHashMap<>();
        GROUP_KEYS.forEach(k -> bucket.put(k, new ArrayList<>()));
        tasks.forEach(t -> bucket.get(groupOf(String.valueOf(t.get("taskType")))).add(t));
        return bucket;
    }

    private static int minFontRpx(String wxss) {
        Matcher m = FONT_SIZE.matcher(wxss);
        int min = Integer.MAX_VALUE;
        while (m.find()) {
            min = Math.min(min, Integer.parseInt(m.group(1)));
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    private static List<String> groupKeys(String js) {
        Matcher m = GROUP_KEY.matcher(js);
        List<String> keys = new ArrayList<>();
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }

    // ------------------------------------------------------------------ http

    private String adminToken() {
        return jwt.issue(JwtPrincipal.staff(DemoStaffIds.ADMIN, TokenType.A, "ALL", List.of())).token();
    }

    private String managerToken() {
        return jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.MANAGER, TokenType.F, "STORE", List.of(DemoCatalogIds.STORE))).token();
    }

    private String frontToken() {
        return jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.FRONT, TokenType.F, "STORE", List.of(DemoCatalogIds.STORE))).token();
    }

    private String therapistToken() {
        return jwt.issue(JwtPrincipal.staff(
                DemoStaffIds.T1, TokenType.T, "SELF", List.of(DemoCatalogIds.STORE))).token();
    }

    private ResponseEntity<Map<String, Object>> get(String path, String bearer) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(jsonHeaders(bearer)), MAP);
    }

    private ResponseEntity<Map<String, Object>> post(String path, Map<String, ?> body, String bearer) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, jsonHeaders(bearer)), MAP);
    }

    private static HttpHeaders jsonHeaders(String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return headers;
    }

    private static Map<String, Object> body(ResponseEntity<Map<String, Object>> res) {
        assertThat(res.getBody()).isNotNull();
        return res.getBody();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> res, HttpStatus expected) {
        assertThat(res.getStatusCode()).isEqualTo(expected);
        Map<String, Object> body = body(res);
        assertThat(body.get("code")).isEqualTo(0);
        return (Map<String, Object>) body.get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> data, String key) {
        return (List<Map<String, Object>>) data.get(key);
    }

    private static int intOf(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static Row row(String kind, String check, boolean pass, String detail) {
        return new Row(kind, check, pass, detail);
    }

    // ------------------------------------------------------------------ render

    private static String renderReport(List<Row> rows, int fullDay, int peak) {
        long ok = rows.stream().filter(r -> r.pass).count();
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
                  <title>PR21 店长 M1</title>
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
                  </style>
                </head>
                <body>
                  <header>
                    <h1>PR21 · 店长 M1（满班率 + 待办）</h1>
                    <p>GET /f/metrics/utilization · GET /f/human-tasks · approve / deny / resolve · 15px / 48px</p>
                    <span class="badge __CLS__">__BADGE__</span>
                  </header>
                  <main>
                    <div class="flow">
                      <div class="st">LEAVE_APPROVE</div>
                      <div class="st">REFUND_APPROVE</div>
                      <div class="st">ORDER_ABNORMAL</div>
                      <div>→ 单一 human_task 队列 →</div>
                      <div class="st act">approve / deny / resolve</div>
                      <div class="st act">全日满班率 __FULL__‱ · 峰值 __PEAK__‱</div>
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
                .replace("__BADGE__", "ALL PASS · " + ok + " / " + rows.size())
                .replace("__FULL__", String.valueOf(fullDay))
                .replace("__PEAK__", String.valueOf(peak))
                .replace("__ROWS__", body);
    }

    private static String renderPreview(int fullDay, List<Map<String, Object>> byHour,
                                        List<Map<String, Object>> tasks) {
        StringBuilder bars = new StringBuilder();
        for (Map<String, Object> h : byHour) {
            int rate = intOf(h.get("rateX10000"));
            int pct = rate / 100;
            String tone = pct >= 85 ? "full" : pct >= 50 ? "busy" : "idle";
            bars.append("<div class='col'><div class='col-text'>").append(pct).append("%</div>")
                    .append("<div class='track'><div class='fill ").append(tone)
                    .append("' style='height:").append(Math.max(12, pct)).append("%'></div></div>")
                    .append("<div class='col-hour'>").append(esc(String.valueOf(h.get("hour"))))
                    .append("</div></div>\n");
        }
        StringBuilder queue = new StringBuilder();
        for (String key : GROUP_KEYS) {
            List<Map<String, Object>> group = tasks.stream()
                    .filter(t -> key.equals(groupOf(String.valueOf(t.get("taskType")))))
                    .toList();
            if (group.isEmpty()) {
                continue;
            }
            queue.append("<div class='group-label'>").append(esc(labelOf(key))).append(" · ")
                    .append(group.size()).append("</div>\n");
            for (Map<String, Object> t : group) {
                queue.append("<div class='task'><div class='task-main'><div class='task-title'>")
                        .append(esc(String.valueOf(t.get("title")))).append("</div>")
                        .append("<div class='task-meta'>").append(esc(String.valueOf(t.get("taskType"))))
                        .append(" · ").append(esc(String.valueOf(t.get("bizKey")))).append("</div></div>")
                        .append("<div class='acts'>").append(actionsOf(key)).append("</div></div>\n");
            }
        }
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                  <meta charset="UTF-8"/>
                  <title>PR21 店长 M1 HTML preview</title>
                  <style>
                    body { margin:0; background:#d9e3dd; font-family:"PingFang SC", sans-serif; color:#14352c; }
                    header { background:#1E5C4A; color:#fff; padding:20px 28px; }
                    header h1 { margin:0; font-size:20px; }
                    header p { margin:6px 0 0; font-size:13px; opacity:.85; }
                    .wrap { display:flex; justify-content:center; padding:28px; }
                    .phone { width:375px; background:#f4f1ea; border-radius:28px; overflow:hidden;
                             box-shadow:0 12px 30px rgba(20,53,44,.18); padding-bottom:16px; }
                    .bar { display:flex; justify-content:space-between; align-items:center;
                           padding:16px 18px; background:#1E5C4A; color:#f4f1ea; }
                    .title { font-size:20px; font-weight:600; }
                    .sub, .rate-cap { font-size:15px; opacity:.85; }
                    .rate { text-align:right; }
                    .rate-num { font-size:28px; font-weight:700; line-height:1.1; }
                    .card { background:#fff; border-radius:14px; margin:12px; padding:12px; }
                    .h2 { font-size:17px; font-weight:600; color:#1E5C4A; margin-bottom:8px; }
                    .chart { display:flex; align-items:flex-end; gap:2px; overflow-x:auto; }
                    .col { flex:0 0 auto; min-width:26px; text-align:center; }
                    .track { height:110px; display:flex; align-items:flex-end;
                             background:#f1f6f4; border-radius:4px; overflow:hidden; }
                    .fill { width:100%; border-radius:4px 4px 0 0; }
                    .fill.full { background:#1E5C4A; }
                    .fill.busy { background:#4f9a80; }
                    .fill.idle { background:#a9cdbf; }
                    .col-text, .col-hour, .legend, .lg { font-size:15px; color:#4a5a54; }
                    .legend { margin-top:8px; }
                    .group-label { font-size:15px; font-weight:600; color:#1E5C4A; padding:8px 0 4px; }
                    .task { display:flex; flex-wrap:wrap; gap:4px 8px;
                            padding:8px 0; border-top:1px solid #e8f1ed; }
                    .task-main { flex:1 1 100%; min-width:0; }
                    .task-title, .task-meta { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
                    .task-title { font-size:16px; font-weight:600; }
                    .task-meta { font-size:15px; color:#7d8b85; }
                    .acts { display:flex; gap:6px; width:100%; justify-content:flex-end; }
                    .btn { min-width:48px; height:48px; line-height:48px; padding:0 12px;
                           background:#1E5C4A; color:#f4f1ea; font-size:15px; border-radius:8px;
                           text-align:center; }
                    .btn.ghost { background:#e8f1ed; color:#1E5C4A; }
                    .tag { min-height:48px; line-height:48px; padding:0 12px; font-size:15px; color:#7d8b85; }
                    .ruler { margin:0 28px 24px; background:#fff; padding:12px 16px; border-radius:10px;
                             font-size:15px; }
                    .mark { display:inline-block; width:48px; height:48px; background:#1E5C4A; color:#fff;
                            text-align:center; line-height:48px; border-radius:8px; margin-right:8px; }
                  </style>
                </head>
                <body>
                  <header>
                    <h1>mini-staff 店长台 M1 HTML preview</h1>
                    <p>WeChat 开发者工具未接入时的页面验收。最小字号 15px，主操作 48px tap target。</p>
                  </header>
                  <div class="wrap">
                    <div class="phone">
                      <div class="bar">
                        <div>
                          <div class="title">店长台 · M1</div>
                          <div class="sub">演示店长 · 2026-08-14</div>
                        </div>
                        <div class="rate">
                          <div class="rate-num">__FULL__%</div>
                          <div class="rate-cap">全日满班率</div>
                        </div>
                      </div>
                      <div class="card">
                        <div class="h2">分时满班率</div>
                        <div class="chart">
                __BARS__        </div>
                        <div class="legend">分母不含 REST（未排班 / 请假不进产能）</div>
                      </div>
                      <div class="card">
                        <div class="h2">待办 · __OPEN__</div>
                __QUEUE__      </div>
                    </div>
                  </div>
                  <div class="ruler">
                    <span class="mark">48</span>
                    通过 / 驳回 / 按完成 ≥ 48×48px · 正文 ≥ 15px · M1 只看满班率与待办，不做 BI
                  </div>
                </body>
                </html>
                """
                .replace("__FULL__", String.valueOf(fullDay / 100))
                .replace("__OPEN__", String.valueOf(tasks.size()))
                .replace("__BARS__", bars)
                .replace("__QUEUE__", queue);
    }

    private static String labelOf(String key) {
        return switch (key) {
            case "leave" -> "请假审批";
            case "refund" -> "退款审批 ≥¥500";
            case "abnormal" -> "异常单";
            default -> "人工队列";
        };
    }

    private static String actionsOf(String key) {
        return switch (key) {
            case "leave", "refund" -> "<div class='btn'>通过</div><div class='btn ghost'>驳回</div>";
            case "abnormal" -> "<div class='btn'>按完成</div><div class='btn ghost'>按取消</div>"
                    + "<div class='btn ghost'>忽略</div>";
            default -> "<div class='tag'>线下处理</div>";
        };
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
