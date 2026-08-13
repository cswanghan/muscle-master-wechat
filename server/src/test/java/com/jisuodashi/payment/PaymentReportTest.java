package com.jisuodashi.payment;

import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.inventory.DelayedJobStore.DelayedJobRow;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.inventory.LockNewResult;
import com.jisuodashi.inventory.OccupyFixtures;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.job.JobRunner;
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

class PaymentReportTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 14);

    @Test
    void writeHtmlReport() throws Exception {
        List<Row> rows = new ArrayList<>();

        Fixture replay = fixture("rpt-replay");
        PaymentDtos.PayResponse p1 = replay.svc.repay(OccupyFixtures.CUSTOMER, replay.locked.orderId(), "r1");
        PaymentDtos.WechatNotifyAck a1 = replay.svc.onWechatNotify(PaymentNotifyTest.body(p1.paymentNo(), 19800), Map.of());
        PaymentDtos.WechatNotifyAck a2 = replay.svc.onWechatNotify(PaymentNotifyTest.body(p1.paymentNo(), 19800), Map.of());
        rows.add(row("NOTIFY", "回调重放按 payment_no 幂等",
                "SUCCESS".equals(a1.code()) && "SUCCESS".equals(a2.code())
                        && replay.payments.listByOrderId(replay.locked.orderId()).size() == 1
                        && "BOOKED".equals(replay.store.findOrderByHoldId(replay.locked.holdId()).status()),
                "ack=" + a1.code() + "/" + a2.code()
                        + " status=" + replay.store.findOrderByHoldId(replay.locked.holdId()).status()));

        Fixture amt = fixture("rpt-amt");
        PaymentDtos.PayResponse pAmt = amt.svc.repay(OccupyFixtures.CUSTOMER, amt.locked.orderId(), "a1");
        PaymentDtos.WechatNotifyAck aAmt = amt.svc.onWechatNotify(PaymentNotifyTest.body(pAmt.paymentNo(), 1), Map.of());
        amt.svc.onWechatNotify(PaymentNotifyTest.body(pAmt.paymentNo(), 1), Map.of());
        rows.add(row("NOTIFY", "金额不符 → FAILED + human_task + APIv3 SUCCESS",
                "SUCCESS".equals(aAmt.code())
                        && Payment.FAILED.equals(amt.payments.findByPaymentNo(pAmt.paymentNo()).status())
                        && "PENDING_PAY".equals(amt.store.findOrderByHoldId(amt.locked.holdId()).status())
                        && amt.payments.listHumanTasks().stream()
                        .filter(t -> PaymentService.TASK_AMOUNT_MISMATCH.equals(t.getTaskType())).count() == 1,
                "pay=" + amt.payments.findByPaymentNo(pAmt.paymentNo()).status()
                        + " order=" + amt.store.findOrderByHoldId(amt.locked.holdId()).status()
                        + " tasks=" + amt.payments.listHumanTasks().size()));

        Fixture closed = fixture("rpt-closed");
        PaymentDtos.PayResponse pClosed = closed.svc.repay(OccupyFixtures.CUSTOMER, closed.locked.orderId(), "c1");
        closed.machine.fire(closed.locked.orderId(), OrderEvent.USER_CANCEL,
                FireContext.customer(OccupyFixtures.CUSTOMER));
        PaymentDtos.WechatNotifyAck aClosed = closed.svc.onWechatNotify(
                PaymentNotifyTest.body(pClosed.paymentNo(), 19800), Map.of());
        boolean refundQueued = closed.payments.listRefundsByOrderId(closed.locked.orderId()).size() == 1
                && Refund.PENDING.equals(closed.payments.listRefundsByOrderId(closed.locked.orderId()).getFirst().status())
                && closed.payments.listWorkflowsByOrderId(closed.locked.orderId()).stream()
                .anyMatch(w -> WorkflowInstance.TYPE_REFUND.equals(w.workflowType()));
        rows.add(row("REFUND", "CLOSED+已扣款 → 入退款队列且不 PAY_SUCCESS",
                "SUCCESS".equals(aClosed.code())
                        && "CLOSED".equals(closed.store.findOrderByHoldId(closed.locked.holdId()).status())
                        && closed.store.occupancies.isEmpty()
                        && refundQueued
                        && closed.payments.findByPaymentNo(pClosed.paymentNo()).success(),
                "order=CLOSED occ=0 refund=PENDING wf=REFUND"));

        Fixture d25 = fixture("rpt-d25");
        PaymentDtos.PayResponse pD25 = d25.svc.repay(OccupyFixtures.CUSTOMER, d25.locked.orderId(), "d1");
        d25.svc.onWechatNotify(PaymentNotifyTest.body(pD25.paymentNo(), 19800), Map.of());
        DelayedJobRow job = d25.store.findJob(d25.store.jobByHold(d25.locked.holdId()).id);
        JobRunner runner = new JobRunner(null, null, d25.store, d25.clock, "t", null, d25.machine);
        int code = runner.dispatch(job);
        runner.completeJob(job, code, null);
        rows.add(row("D25", "先支付再跑原 RELEASE_LOCK → 订单 BOOKED、job DONE",
                "BOOKED".equals(d25.store.findOrderByHoldId(d25.locked.holdId()).status())
                        && "DONE".equals(d25.store.findJob(job.id()).status())
                        && code == ErrorCodes.ILLEGAL_TRANSITION
                        && d25.store.occupancies.size() == 10,
                "order=BOOKED job=DONE fire=40904 occ=10"));

        Fixture reuse = fixture("rpt-reuse");
        PaymentDtos.PayResponse r1 = reuse.svc.repay(OccupyFixtures.CUSTOMER, reuse.locked.orderId(), "u1");
        PaymentDtos.PayResponse r2 = reuse.svc.repay(OccupyFixtures.CUSTOMER, reuse.locked.orderId(), "u2");
        rows.add(row("REPAY", "1:1 prepay 未过期复用",
                r1.paymentNo().equals(r2.paymentNo()) && Boolean.TRUE.equals(r2.reused())
                        && r2.payParams().get("package").startsWith("prepay_id="),
                "paymentNo=" + r1.paymentNo() + " reused=" + r2.reused()));

        String html = render(rows);
        Path docs = resolveRepoRoot().resolve("docs/test-cases");
        Files.createDirectories(docs);
        Path report = docs.resolve("pr-8-notify-pay.html");
        Files.writeString(report, html, StandardCharsets.UTF_8);
        Files.createDirectories(resolveTargetDir());
        Files.writeString(resolveTargetDir().resolve("pr-8-notify-pay.html"), html, StandardCharsets.UTF_8);

        List<Row> failed = rows.stream().filter(r -> !r.pass).toList();
        assertThat(failed).as("pr-8 report failures: %s", failed).isEmpty();
        assertThat(html).contains("payment_no").contains("RELEASE_LOCK").contains("REFUND");
        assertThat(report).exists();
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
        return new Fixture(store, machine, payments, clock, svc, locked);
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
                  <title>PR8 支付 · JSAPI / notify / 关单退款</title>
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
                    .callout { background:#fff; border-left:4px solid #1E5C4A; padding:12px 16px;
                               margin:16px 0; }
                    .warn { border-left-color:#e85d4c; }
                    .flow { display:flex; gap:8px; flex-wrap:wrap; align-items:center; margin:12px 0; }
                    .st { padding:8px 10px; border-radius:8px; font-size:12px; font-weight:700;
                          border:2px solid var(--brand); background:#e8f1ed; }
                    .st.pay { background:#1E5C4A; color:#fff; }
                    .st.bad { background:#fdecea; border-color:#e85d4c; color:#8a2418; }
                    .arr { font-size:12px; color:#4a6a5f; }
                    code { font-family: ui-monospace, Menlo, monospace; font-size:12px; }
                    footer { padding:0 40px 32px; color:#6b7c75; font-size:12px; }
                  </style>
                </head>
                <body>
                  <header>
                    <h1>PR8 · JSAPI / notify / 关单自动退款</h1>
                    <p>§3.5 onWechatNotify · D17 直连单商户号 · D23 GET /f/payments 轮询 · D25 先付后 RELEASE_LOCK</p>
                    <span class="badge __CLS__">__BADGE__ · __OK__ / __N__</span>
                  </header>
                  <main>
                    <div class="flow">
                      <div class="st">lockNew</div>
                      <div class="arr">→</div>
                      <div class="st">PENDING_PAY</div>
                      <div class="arr">POST /c/bookings/{id}/pay</div>
                      <div class="st pay">mock JSAPI</div>
                      <div class="arr">notify</div>
                      <div class="st pay">BOOKED</div>
                    </div>
                    <div class="flow">
                      <div class="st">CLOSED</div>
                      <div class="arr">微信已扣款</div>
                      <div class="st">payment SUCCESS</div>
                      <div class="arr">同 TX</div>
                      <div class="st">workflow REFUND + refund PENDING</div>
                      <div class="arr">禁止</div>
                      <div class="st bad">fire(PAY_SUCCESS)</div>
                    </div>
                    <div class="callout">
                      <strong>§3.5 / D25</strong>
                      <div><code>SELECT payment FOR UPDATE</code>；已 SUCCESS 直接 ack；金额不符
                      <code>FAILED</code> + <code>human_task</code> 仍回 <code>{"code":"SUCCESS"}</code>。
                      先支付再跑原 <code>RELEASE_LOCK</code> → 订单仍 <code>BOOKED</code>，job <code>DONE</code>（迟到
                      <code>PAY_TIMEOUT</code> 为 40904）。</div>
                    </div>
                    <div class="callout warn">
                      <strong>dev 无真实微信</strong>
                      <div><code>app.wechat.mock=true</code> 返回 mock JSAPI；notify 收解密后的
                      <code>out_trade_no / amount_fen</code>。D17 平台默认 <code>mchid</code>，店级可空。</div>
                    </div>
                    <h2>验收项</h2>
                    <table>
                      <thead><tr><th>类</th><th>检查</th><th>细节</th><th>结果</th></tr></thead>
                      <tbody>
                __ROWS__      </tbody>
                    </table>
                  </main>
                  <footer>Generated by PaymentReportTest · §3.5 onWechatNotify · D17 / D23 / D25</footer>
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

    private record Fixture(
            InMemorySlotOccupyStore store,
            OrderStateMachine machine,
            InMemoryPaymentStore payments,
            AppClock clock,
            PaymentService svc,
            LockNewResult locked
    ) {
    }
}
