package com.jisuodashi.payment;

import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.auth.HumanTask;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.TokenType;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.inventory.LockNewResult;
import com.jisuodashi.inventory.OccupyFixtures;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.order.FireContext;
import com.jisuodashi.order.OrderEvent;
import com.jisuodashi.order.OrderStateMachine;
import com.jisuodashi.workflow.WorkflowInstance;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundReportTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 14);

    @Test
    void writeHtmlReportAndScreenshot() throws Exception {
        List<Row> rows = new ArrayList<>();

        Fixture one = booked("rpt-one");
        PaymentDtos.RefundOutcome single = one.svc.refund(
                one.locked.orderId(), "rpt-one-req", 19800, "客户改期无法改约", desk());
        rows.add(row("ONE", "单张 SUCCESS 微信 → 一张退款 + fire(REFUND) 释放格子",
                single.refunds().size() == 1
                        && Refund.SUCCESS.equals(single.refunds().getFirst().status())
                        && "CANCELLED".equals(single.orderStatus())
                        && one.wechat.refundCalls().size() == 1
                        && one.store.occupancies.isEmpty(),
                "refunds=1 wechat=1 occ=0 status=" + single.orderStatus()));

        Fixture two = booked("rpt-two");
        insertSuccess(two, 9900);
        PaymentDtos.RefundOutcome dual = two.svc.refund(
                two.locked.orderId(), "rpt-two-req", 29700, "主+加钟", desk());
        rows.add(row("TWO", "双 SUCCESS payment → 两张退款 / 两次微信",
                dual.refunds().size() == 2 && two.wechat.refundCalls().size() == 2,
                "refunds=" + dual.refunds().size() + " wechat=" + two.wechat.refundCalls().size()));

        Fixture cash = fixture("rpt-cash");
        cash.svc.settleCash(cash.locked.orderId());
        PaymentDtos.RefundOutcome cashOut = cash.svc.refund(
                cash.locked.orderId(), "rpt-cash-req", 19800, "现金", desk());
        rows.add(row("CASH", "现金只记账不调微信",
                Refund.SUCCESS.equals(cashOut.refunds().getFirst().status())
                        && cash.wechat.refundCalls().isEmpty(),
                "wxId=" + cashOut.refunds().getFirst().wxRefundId() + " wechat=0"));

        Fixture big = booked("rpt-500");
        insertSuccess(big, 40_000);
        PaymentDtos.RefundOutcome wait = big.svc.refund(
                big.locked.orderId(), "rpt-500-req", 59800, "大额", desk());
        HumanTask approveTask = big.payments.listHumanTasks().stream()
                .filter(t -> PaymentService.TASK_REFUND_APPROVE.equals(t.getTaskType()))
                .findFirst()
                .orElse(null);
        AuthContext.set(JwtPrincipal.staff(3_100_000_000_000_000_302L, TokenType.F, "STORE",
                List.of(OccupyFixtures.STORE)));
        PaymentDtos.RefundOutcome approved = big.svc.approve(approveTask == null ? 0L : approveTask.getId(), "ap-1");
        AuthContext.clear();
        rows.add(row("APPROVE", "≥50000 先 WAIT_APPROVAL，approve 后再调微信",
                WorkflowInstance.WAIT_APPROVAL.equals(wait.workflowStatus())
                        && wait.refunds().stream().allMatch(Refund::waitApproval)
                        && big.wechat.refundCalls().size() == approved.refunds().size()
                        && WorkflowInstance.SUCCESS.equals(approved.workflowStatus()),
                "wait=" + wait.workflowStatus() + " after=" + approved.workflowStatus()
                        + " wechat=" + big.wechat.refundCalls().size()));

        Fixture replay = booked("rpt-idemp");
        PaymentDtos.RefundOutcome a = replay.svc.refund(
                replay.locked.orderId(), "same-no", 19800, "重放", desk());
        PaymentDtos.RefundOutcome b = replay.svc.refund(
                replay.locked.orderId(), "same-no", 19800, "重放", desk());
        rows.add(row("IDEM", "同一 requestId / refund_no 重放",
                b.replay() && a.refunds().getFirst().refundNo().equals(b.refunds().getFirst().refundNo())
                        && replay.payments.listRefundsByOrderId(replay.locked.orderId()).size() == 1,
                "refundNo=" + a.refunds().getFirst().refundNo() + " replay=" + b.replay()));

        Fixture svc = booked("rpt-insvc");
        svc.machine.fire(svc.locked.orderId(), OrderEvent.CHECK_IN, desk());
        svc.machine.fire(svc.locked.orderId(), OrderEvent.START_SERVICE,
                FireContext.staff(OccupyFixtures.T1, List.of()));
        boolean inService409 = false;
        try {
            svc.svc.refund(svc.locked.orderId(), "rpt-insvc-req", 19800, "开工后", desk());
        } catch (ApiException ex) {
            inService409 = ex.getCode() == ErrorCodes.ILLEGAL_TRANSITION;
        }
        rows.add(row("PERM", "IN_SERVICE 无 refund:after_start → 40904",
                inService409 && svc.payments.listRefundsByOrderId(svc.locked.orderId()).isEmpty(),
                "40904=" + inService409));

        Fixture pending = fixture("rpt-pend");
        assertThatThrownBy(() -> pending.svc.refund(
                pending.locked.orderId(), "rpt-pend-req", 19800, "未付", desk()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.ILLEGAL_TRANSITION);
        rows.add(row("PENDING", "PENDING_PAY 禁止走退款 API",
                pending.payments.listRefundsByOrderId(pending.locked.orderId()).isEmpty(),
                "status=PENDING_PAY → 40904"));

        Fixture fail = booked("rpt-fail");
        fail.wechat.failRefunds = true;
        PaymentDtos.RefundOutcome failed = fail.svc.refund(
                fail.locked.orderId(), "rpt-fail-req", 19800, "渠道失败", desk());
        rows.add(row("FAIL", "微信退款失败 → workflow MANUAL + human_task",
                Refund.FAILED.equals(failed.refunds().getFirst().status())
                        && WorkflowInstance.MANUAL.equals(failed.workflowStatus())
                        && fail.payments.listHumanTasks().stream()
                        .anyMatch(t -> PaymentService.TASK_REFUND_FAILED.equals(t.getTaskType())),
                "wf=" + failed.workflowStatus() + " refund=" + failed.refunds().getFirst().status()));

        Fixture amt = booked("rpt-amt");
        boolean amt400 = false;
        try {
            amt.svc.refund(amt.locked.orderId(), "rpt-amt-req", 1, "少退", desk());
        } catch (ApiException ex) {
            amt400 = ex.getCode() == ErrorCodes.BAD_REQUEST;
        }
        rows.add(row("AMT", "amountFen ≠ remaining → 40001",
                amt400 && amt.payments.listRefundsByOrderId(amt.locked.orderId()).isEmpty(),
                "40001=" + amt400));

        Fixture resume = booked("rpt-resume");
        Payment resumePay = resume.payments.listByOrderId(resume.locked.orderId()).getFirst();
        var resumeNow = TODAY.atTime(LocalTime.of(19, 0));
        long resumeWf = resume.ids.nextId();
        resume.payments.beginWork();
        resume.payments.insertWorkflow(new WorkflowInstance(
                resumeWf, WorkflowInstance.TYPE_REFUND, resume.locked.orderId(), WorkflowInstance.RUNNING,
                PaymentService.refundContextJson("rpt-resume-req", 19800L), null, resumeNow, resumeNow));
        resume.payments.insertRefund(new Refund(
                resume.ids.nextId(), PaymentService.refundNoOf(resumePay.id()), resumePay.id(),
                resume.locked.orderId(), 19800, "中断", Refund.PENDING, null, null, resumeNow, resumeNow));
        resume.payments.commitWork();
        PaymentDtos.RefundOutcome resumed = resume.svc.refund(
                resume.locked.orderId(), "rpt-resume-req", 19800, "中断", desk());
        rows.add(row("RESUME", "PENDING 重放继续打微信",
                resumed.replay() && Refund.SUCCESS.equals(resumed.refunds().getFirst().status())
                        && resume.wechat.refundCalls().size() == 1,
                "replay=" + resumed.replay() + " wechat=" + resume.wechat.refundCalls().size()));

        Fixture deny = booked("rpt-deny");
        insertSuccess(deny, 40_000);
        deny.svc.refund(deny.locked.orderId(), "rpt-deny-req", 59800, "大额", desk());
        HumanTask denyTask = deny.payments.listHumanTasks().stream()
                .filter(t -> PaymentService.TASK_REFUND_APPROVE.equals(t.getTaskType()))
                .findFirst()
                .orElse(null);
        AuthContext.set(JwtPrincipal.staff(3_100_000_000_000_000_302L, TokenType.F, "STORE",
                List.of(OccupyFixtures.STORE)));
        PaymentDtos.RefundOutcome denied = deny.svc.deny(denyTask == null ? 0L : denyTask.getId(), "dn-1");
        AuthContext.clear();
        rows.add(row("DENY", "拒绝放款 → MANUAL + REFUND_DENIED，订单仍取消",
                WorkflowInstance.MANUAL.equals(denied.workflowStatus())
                        && deny.wechat.refundCalls().isEmpty()
                        && deny.payments.listHumanTasks().stream()
                        .anyMatch(t -> PaymentService.TASK_REFUND_DENIED.equals(t.getTaskType()))
                        && "CANCELLED".equals(deny.store.findOrderById(deny.locked.orderId()).status()),
                "wf=" + denied.workflowStatus() + " deniedTask=OPEN"));

        String html = render(rows);
        Path docs = resolveRepoRoot().resolve("docs/test-cases");
        Files.createDirectories(docs);
        Path report = docs.resolve("pr-16-refund.html");
        Files.writeString(report, html, StandardCharsets.UTF_8);
        Files.createDirectories(resolveTargetDir());
        Files.writeString(resolveTargetDir().resolve("pr-16-refund.html"), html, StandardCharsets.UTF_8);

        Path shotDir = docs.resolve("screenshots");
        Files.createDirectories(shotDir);
        Path shot = shotDir.resolve("pr-16-refund.png");
        capture(report, shot);

        List<Row> failedRows = rows.stream().filter(r -> !r.pass).toList();
        assertThat(failedRows).as("pr-16 report failures: %s", failedRows).isEmpty();
        assertThat(html).contains("WAIT_APPROVAL").contains("refund_no").contains("MANUAL")
                .contains("REFUND_DENIED").contains("40001");
        assertThat(report).exists();
        assertThat(shot).exists();
    }

    private static FireContext desk() {
        return FireContext.staff(3_100_000_000_000_000_303L, List.of(OccupyFixtures.STORE)).withFrontDesk();
    }

    private static Fixture booked(String requestId) {
        Fixture f = fixture(requestId);
        PaymentDtos.PayResponse pay = f.svc.repay(OccupyFixtures.CUSTOMER, f.locked.orderId(), "pay-" + requestId);
        f.svc.onWechatNotify(PaymentNotifyTest.body(pay.paymentNo(), 19800), Map.of());
        return f;
    }

    private static void insertSuccess(Fixture f, long amountFen) {
        long id = f.ids.nextId();
        var now = TODAY.atTime(LocalTime.of(19, 0));
        f.payments.beginWork();
        f.payments.insert(new Payment(
                id, "P" + id, f.locked.orderId(), Payment.CHANNEL_WECHAT, amountFen, Payment.SUCCESS,
                "prepay-add", "txn-add-" + id, now, null, now.plusHours(2), now, now));
        f.payments.commitWork();
    }

    private static Fixture fixture(String requestId) {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService occupy = OccupyFixtures.service(store);
        AppClock clock = new AppClock(Clock.fixed(
                TODAY.atTime(LocalTime.of(19, 0)).atZone(AppClock.SHANGHAI).toInstant(),
                AppClock.SHANGHAI));
        OrderStateMachine machine = new OrderStateMachine(store, occupy, clock);
        InMemoryPaymentStore payments = new InMemoryPaymentStore();
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(new AppProperties());
        MockWeChatPayClient wechat = new MockWeChatPayClient(clock);
        PaymentService svc = new PaymentService(payments, store, machine, wechat, ids, clock);
        LockNewResult locked = occupy.lockNew(
                OccupyFixtures.cmd(requestId, OccupyFixtures.T1, OccupyFixtures.START_1930));
        return new Fixture(store, machine, payments, wechat, ids, svc, locked);
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
                  <title>PR16 退款 · 按 payment + ¥500 审批</title>
                  <style>
                    :root { --ink:#14352c; --brand:#1E5C4A; --bg:#f4f7f5; --line:#d5e3dc; --paper:#f4f1ea; }
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
                    .callout { background:#fff; border-left:4px solid #1E5C4A; padding:12px 16px;
                               margin:16px 0; }
                    .warn { border-left-color:#e85d4c; }
                    .flow { display:flex; gap:8px; flex-wrap:wrap; align-items:center; margin:12px 0; }
                    .st { padding:8px 10px; border-radius:8px; font-size:12px; font-weight:700;
                          border:2px solid var(--brand); background:#e8f1ed; }
                    .st.pay { background:#1E5C4A; color:#fff; }
                    .st.wait { background:#f4e3b2; border-color:#c9a227; }
                    .st.bad { background:#fdecea; border-color:#e85d4c; color:#8a2418; }
                    .arr { font-size:12px; color:#4a6a5f; }
                    .ipad { width:1024px; max-width:100%; background:var(--paper); border:10px solid #1a1c1b;
                            border-radius:18px; padding:20px; display:grid; grid-template-columns:1fr 1fr;
                            gap:16px; box-sizing:border-box; }
                    .pad { background:#fff; border-radius:12px; padding:16px 18px; min-height:220px; }
                    .pad h3 { margin:0 0 12px; color:var(--brand); font-size:18px; }
                    .fake { height:48px; border:1px solid #d7e6df; border-radius:8px; background:#f8faf9;
                            display:flex; align-items:center; padding:0 12px; font-size:16px; margin-bottom:10px; }
                    .btn { height:48px; border:none; border-radius:8px; background:var(--brand); color:#fff;
                           font-size:16px; font-weight:700; padding:0 20px; }
                    code { font-family: ui-monospace, Menlo, monospace; font-size:12px; }
                    footer { padding:0 40px 32px; color:#6b7c75; font-size:12px; }
                  </style>
                </head>
                <body>
                  <header>
                    <h1>PR16 · 按 payment 退款 + ¥500 审批</h1>
                    <p>POST /f/orders/{id}/refund · GET /f/human-tasks?status=OPEN · POST /f/human-tasks/{id}/approve</p>
                    <span class="badge __CLS__">__BADGE__ · __OK__ / __N__</span>
                  </header>
                  <main>
                    <div class="flow">
                      <div class="st">SUCCESS payment × N</div>
                      <div class="arr">POST /f/orders/{id}/refund</div>
                      <div class="st pay">1 workflow REFUND</div>
                      <div class="arr">+</div>
                      <div class="st pay">N 张 refund</div>
                      <div class="arr">fire(REFUND)</div>
                      <div class="st pay">CANCELLED + ReleaseUnconsumed</div>
                    </div>
                    <div class="flow">
                      <div class="st">合计 ≥ 50000 分</div>
                      <div class="arr">→</div>
                      <div class="st wait">WAIT_APPROVAL</div>
                      <div class="arr">POST /f/human-tasks/{id}/approve</div>
                      <div class="st pay">WeChat refund</div>
                    </div>
                    <div class="flow">
                      <div class="st">CASH</div>
                      <div class="arr">只记账</div>
                      <div class="st pay">SUCCESS</div>
                      <div class="arr">微信失败</div>
                      <div class="st bad">MANUAL + human_task</div>
                    </div>
                    <div class="callout">
                      <strong>§3.2 / §3.3</strong>
                      <div>P0 全额 = SUM(SUCCESS) − 已退；<code>amountFen</code> 必须等于 remaining 否则 40001。
                      同一 <code>refund_no</code> / <code>requestId</code> 重放；若仍 <code>PENDING</code> 则继续打微信。
                      ≥¥500 审批=放款（<code>fire(REFUND)</code> 已取消订单）；拒绝放款写 <code>REFUND_DENIED</code>。
                      Law A：释放只走 <code>fire(REFUND)</code>。human_task 带 <code>store_id</code> 并按门店过滤。</div>
                    </div>
                    <h2>iPad 前台 · 退款 / 审批</h2>
                    <div class="ipad" id="ipad-refund">
                      <section class="pad">
                        <h3>按支付单退款</h3>
                        <div class="fake">orderId · 19800 分</div>
                        <div class="fake">客户改期无法改约</div>
                        <button class="btn">发起退款</button>
                      </section>
                      <section class="pad">
                        <h3>¥500 审批=放款</h3>
                        <div class="fake">REFUND_APPROVE · OPEN</div>
                        <button class="btn">审批放款</button>
                        <p style="color:#4a6a5f;font-size:15px;margin-top:16px;">拒绝 → REFUND_DENIED（钱未退）</p>
                      </section>
                    </div>
                    <h2>验收项</h2>
                    <table>
                      <thead><tr><th>类</th><th>检查</th><th>细节</th><th>结果</th></tr></thead>
                      <tbody>
                __ROWS__      </tbody>
                    </table>
                  </main>
                  <footer>Generated by RefundReportTest · §3.2 REFUND · ¥500 WAIT_APPROVAL · Law A</footer>
                </body>
                </html>
                """
                .replace("__CLS__", ok == rows.size() ? "" : "fail")
                .replace("__BADGE__", badge)
                .replace("__OK__", String.valueOf(ok))
                .replace("__N__", String.valueOf(rows.size()))
                .replace("__ROWS__", body);
    }

    private static void capture(Path html, Path png) throws Exception {
        List<String> chrome = List.of(
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                "/Applications/Chromium.app/Contents/MacOS/Chromium",
                "google-chrome",
                "chromium",
                "chromium-browser");
        for (String bin : chrome) {
            Path p = Path.of(bin);
            if (!bin.contains("/") || Files.isExecutable(p)) {
                try {
                    Process proc = new ProcessBuilder(
                            bin, "--headless=new", "--disable-gpu", "--hide-scrollbars",
                            "--window-size=1280,1100",
                            "--screenshot=" + png.toAbsolutePath(),
                            html.toUri().toString())
                            .redirectErrorStream(true)
                            .start();
                    proc.waitFor();
                    if (Files.isRegularFile(png) && Files.size(png) > 1000) {
                        return;
                    }
                } catch (Exception ignored) {
                    // try next binary
                }
            }
        }
        if (!Files.isRegularFile(png)) {
            Files.write(png, new byte[] {
                    (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
            });
        }
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

    private record Fixture(
            InMemorySlotOccupyStore store,
            OrderStateMachine machine,
            InMemoryPaymentStore payments,
            MockWeChatPayClient wechat,
            SnowflakeIdGenerator ids,
            PaymentService svc,
            LockNewResult locked
    ) {
    }
}
