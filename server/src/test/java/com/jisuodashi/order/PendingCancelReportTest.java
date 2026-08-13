package com.jisuodashi.order;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.inventory.LockNewResult;
import com.jisuodashi.inventory.OccupyFixtures;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.inventory.SlotStatus;
import com.jisuodashi.job.JobRunner;
import com.jisuodashi.job.SlotGenerateJob;
import com.jisuodashi.job.SlotScanJob;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static com.jisuodashi.inventory.OccupyFixtures.START_1930;
import static com.jisuodashi.inventory.OccupyFixtures.T1;
import static org.assertj.core.api.Assertions.assertThat;

class PendingCancelReportTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 14);

    @Test
    void writeHtmlReport() throws Exception {
        List<Row> rows = new ArrayList<>();

        Fixture cancel = fixture("rpt7-cancel", 52);
        FireResult closed = cancel.machine.fire(
                cancel.locked.orderId(), OrderEvent.USER_CANCEL, FireContext.customer(OccupyFixtures.CUSTOMER));
        rows.add(new Row("B", "PENDING_PAY + USER_CANCEL → CLOSED + ReleaseLock",
                closed.to() == OrderStatus.CLOSED
                        && cancel.store.occupancies.isEmpty()
                        && SlotStatus.FREE.equals(cancel.store.therapistSlot(T1, DAY, 52).status),
                "status=" + cancel.store.findOrderByHoldId(cancel.locked.holdId()).status()
                        + " occ=" + cancel.store.occupancies.size()));

        Fixture booked = fixture("rpt7-booked", 56);
        booked.machine.fire(booked.locked.orderId(), OrderEvent.PAY_SUCCESS);
        int bookedCode = fireCode(booked.machine, booked.locked.orderId(), OrderEvent.USER_CANCEL);
        rows.add(new Row("B", "BOOKED + USER_CANCEL → 40904，占用保留",
                bookedCode == ErrorCodes.ILLEGAL_TRANSITION
                        && "BOOKED".equals(booked.store.findOrderByHoldId(booked.locked.holdId()).status())
                        && booked.store.occupancies.size() == 10,
                "code=" + bookedCode + " occ=" + booked.store.occupancies.size()));

        Fixture timeout = fixture("rpt7-timeout", START_1930);
        timeout.store.jobByHold(timeout.locked.holdId()).runAt = DAY.atTime(18, 50);
        int drained = timeout.runner.drainDueJobs();
        rows.add(new Row("A", "RELEASE_LOCK job fire(PAY_TIMEOUT) → CLOSED",
                drained == 1
                        && "CLOSED".equals(timeout.store.findOrderByHoldId(timeout.locked.holdId()).status())
                        && "DONE".equals(timeout.store.jobByHold(timeout.locked.holdId()).status)
                        && timeout.store.occupancies.isEmpty(),
                "job=" + timeout.store.jobByHold(timeout.locked.holdId()).status
                        + " status=" + timeout.store.findOrderByHoldId(timeout.locked.holdId()).status()));

        Fixture paid = fixture("rpt7-paid-expire", 60);
        paid.machine.fire(paid.locked.orderId(), OrderEvent.PAY_SUCCESS);
        paid.store.jobByHold(paid.locked.holdId()).status = "PENDING";
        paid.store.jobByHold(paid.locked.holdId()).runAt = DAY.atTime(18, 50);
        paid.runner.drainDueJobs();
        rows.add(new Row("A", "先支付再到期原 RELEASE_LOCK → BOOKED + job DONE",
                "BOOKED".equals(paid.store.findOrderByHoldId(paid.locked.holdId()).status())
                        && "DONE".equals(paid.store.jobByHold(paid.locked.holdId()).status)
                        && paid.store.occupancies.size() == 10
                        && SlotStatus.BOOKED.equals(paid.store.therapistSlot(T1, DAY, 60).status),
                "status=BOOKED job=DONE occ=" + paid.store.occupancies.size()));

        Fixture scan = fixture("rpt7-scan", 64);
        scan.store.expireHold(scan.locked.holdId(), DAY.atTime(18, 50));
        var scanResult = new SlotScanJob(scan.occupy, scan.machine).run();
        rows.add(new Row("C", "扫描 PENDING_PAY 过期 hold fire(PAY_TIMEOUT) → CLOSED",
                scanResult.pendingReleased() == 1
                        && "CLOSED".equals(scan.store.findOrderByHoldId(scan.locked.holdId()).status())
                        && scan.store.occupancies.isEmpty(),
                "pending=" + scanResult.pendingReleased()
                        + " status=" + scan.store.findOrderByHoldId(scan.locked.holdId()).status()));

        Path jobSrc = resolveRepoRoot().resolve("server/src/main/java/com/jisuodashi/job/JobRunner.java");
        String jobText = Files.readString(jobSrc);
        rows.add(new Row("LAW A", "JobRunner 只 fire(PAY_TIMEOUT)，不先 ReleaseLock",
                jobText.contains("OrderEvent.PAY_TIMEOUT")
                        && jobText.contains("machine.fire(")
                        && !jobText.contains("releaseLock("),
                "dispatch → fire"));

        Path occupySrc = resolveRepoRoot().resolve("server/src/main/java/com/jisuodashi/inventory/SlotOccupyService.java");
        String occupyText = Files.readString(occupySrc);
        rows.add(new Row("LAW A", "Release* 禁止 fire / 不依赖 order 包",
                !occupyText.contains("import com.jisuodashi.order")
                        && !occupyText.contains("OrderStateMachine")
                        && occupyText.contains("MUST NOT"),
                "inventory 无 fire"));

        String html = render(rows);
        Path docs = resolveRepoRoot().resolve("docs/test-cases");
        Files.createDirectories(docs);
        Path report = docs.resolve("pr-7-cancel-timeout.html");
        Files.writeString(report, html, StandardCharsets.UTF_8);
        Files.createDirectories(resolveTargetDir());
        Files.writeString(resolveTargetDir().resolve("pr-7-cancel-timeout.html"), html, StandardCharsets.UTF_8);

        List<Row> failed = rows.stream().filter(r -> !r.pass).toList();
        assertThat(failed).as("pr-7 report failures: %s", failed).isEmpty();
        assertThat(html).contains("PAY_TIMEOUT").contains("USER_CANCEL").contains("40904");
        assertThat(report).exists();
    }

    private static int fireCode(OrderStateMachine machine, long orderId, OrderEvent event) {
        try {
            machine.fire(orderId, event, FireContext.customer(OccupyFixtures.CUSTOMER));
            return ErrorCodes.OK;
        } catch (ApiException ex) {
            return ex.getCode();
        }
    }

    private static Fixture fixture(String requestId, int startSlotNo) {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService occupy = OccupyFixtures.service(store);
        AppClock clock = new AppClock(Clock.fixed(
                LocalDate.of(2026, 8, 14).atTime(LocalTime.of(19, 0)).atZone(AppClock.SHANGHAI).toInstant(),
                AppClock.SHANGHAI));
        OrderStateMachine machine = new OrderStateMachine(store, occupy, clock);
        LockNewResult locked = occupy.lockNew(OccupyFixtures.cmd(requestId, T1, startSlotNo));
        JobRunner runner = new JobRunner(
                new SlotGenerateJob(null),
                new SlotScanJob(occupy, machine),
                store,
                clock,
                "w-test",
                null,
                machine);
        return new Fixture(store, occupy, machine, locked, runner);
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
                  <title>PR7 未支付取消 / 超时</title>
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
                    .diagram { background:#fff; padding:20px 16px 8px; border-radius:8px;
                               box-shadow:0 1px 3px rgba(20,53,44,.06); overflow-x:auto; }
                    .row { display:flex; gap:10px; justify-content:center; flex-wrap:wrap;
                           margin-bottom:8px; align-items:center; }
                    .st { min-width:110px; text-align:center; padding:10px 8px; border-radius:8px;
                          font-size:12px; font-weight:700; border:2px solid var(--brand); background:#e8f1ed; }
                    .st.start { background:#1E5C4A; color:#fff; }
                    .st.end { background:#2fbf71; color:#062014; border-color:#2fbf71; }
                    .st.closed { background:#dfe6e3; border-color:#8aa197; }
                    .arr { font-size:11px; color:#4a6a5f; text-align:center; min-width:90px; }
                    code { font-family: ui-monospace, Menlo, monospace; font-size:12px; }
                    footer { padding:0 40px 32px; color:#6b7c75; font-size:12px; }
                  </style>
                </head>
                <body>
                  <header>
                    <h1>PR7 · 未支付取消 / 超时（不依赖微信）</h1>
                    <p>POST /c/bookings/{id}/cancel · RELEASE_LOCK 只 fire(PAY_TIMEOUT) · 40904 → job DONE</p>
                    <span class="badge __CLS__">__BADGE__ · __OK__ / __N__</span>
                  </header>
                  <main>
                    <div class="diagram">
                      <div class="row">
                        <div class="st start">lockNew</div>
                        <div class="arr">→</div>
                        <div class="st">PENDING_PAY</div>
                        <div class="arr">USER_CANCEL / PAY_TIMEOUT</div>
                        <div class="st closed">CLOSED</div>
                        <div class="arr">ReleaseLock</div>
                        <div class="st end">FREE</div>
                      </div>
                      <div class="row">
                        <div class="st">PENDING_PAY</div>
                        <div class="arr">PAY_SUCCESS</div>
                        <div class="st">BOOKED</div>
                        <div class="arr">迟到 RELEASE_LOCK</div>
                        <div class="st">40904 → DONE</div>
                        <div class="arr">占用保留</div>
                        <div class="st">BOOKED</div>
                      </div>
                    </div>
                    <div class="callout">
                      <strong>Law A / D25 / D16</strong>
                      <div>Job / API 只 <code>fire(EVENT)</code>。<code>Release*</code> 禁止再 fire。
                      <code>BOOKED + PAY_TIMEOUT</code> 得 <code>40904</code>，Job 记 <code>DONE</code>，不刷 FAILED。</div>
                    </div>
                    <div class="callout">
                      <strong>三条未支付释放路径</strong>
                      <div>A <code>delayed_job RELEASE_LOCK</code> → <code>fire(PAY_TIMEOUT)</code>；
                      B <code>POST /c/bookings/{id}/cancel</code> → <code>fire(USER_CANCEL)</code>；
                      C 每 5 分钟扫描 PENDING_PAY 过期 hold → <code>fire(PAY_TIMEOUT)</code>。</div>
                    </div>
                    <h2>验收项</h2>
                    <table>
                      <thead><tr><th>路径</th><th>检查</th><th>细节</th><th>结果</th></tr></thead>
                      <tbody>
                __ROWS__      </tbody>
                    </table>
                  </main>
                  <footer>Generated by PendingCancelReportTest · PR7 · D16 40904=DONE · D25 Law A</footer>
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
            SlotOccupyService occupy,
            OrderStateMachine machine,
            LockNewResult locked,
            JobRunner runner
    ) {
    }
}
