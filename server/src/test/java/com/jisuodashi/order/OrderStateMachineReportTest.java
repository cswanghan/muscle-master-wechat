package com.jisuodashi.order;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.inventory.LockNewResult;
import com.jisuodashi.inventory.OccupyFixtures;
import com.jisuodashi.inventory.SlotOccupyService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderStateMachineReportTest {

    @Test
    void writeHtmlReport() throws Exception {
        OrderStateMachine table = new OrderStateMachine();
        List<Row> rows = new ArrayList<>();

        int listedOk = 0;
        for (OrderTransition t : OrderStateMachine.transfers()) {
            OrderTransition got = table.fire(t.from(), t.event());
            boolean pass = got.to() == t.to();
            if (pass) {
                listedOk++;
            }
            rows.add(new Row("TABLE", t.from() + " + " + t.event() + " → " + t.to(),
                    pass, sides(t)));
        }

        int illegal = 0;
        int illegalPass = 0;
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderEvent event : OrderEvent.values()) {
                if (OrderStateMachine.listed(from, event)) {
                    continue;
                }
                illegal++;
                try {
                    table.fire(from, event);
                } catch (ApiException ex) {
                    if (ex.getCode() == ErrorCodes.ILLEGAL_TRANSITION) {
                        illegalPass++;
                    }
                }
            }
        }
        rows.add(new Row("CLOSED", "未列出 (from,event) 全部 40904",
                illegal == illegalPass && illegal > 100,
                "illegal=" + illegal + " 40904=" + illegalPass));

        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService occupy = OccupyFixtures.service(store);
        AppClock clock = new AppClock(Clock.fixed(
                LocalDate.of(2026, 8, 14).atTime(LocalTime.of(19, 0)).atZone(AppClock.SHANGHAI).toInstant(),
                AppClock.SHANGHAI));
        OrderStateMachine machine = new OrderStateMachine(store, occupy, clock);
        LockNewResult locked = occupy.lockNew(OccupyFixtures.cmd("rpt-pay", OccupyFixtures.T1, OccupyFixtures.START_1930));
        FireResult paid = machine.fire(locked.orderId(), OrderEvent.PAY_SUCCESS);
        rows.add(new Row("SIDE", "PENDING_PAY + PAY_SUCCESS → BOOKED + confirmPaidSlots",
                paid.to() == OrderStatus.BOOKED && "DONE".equals(store.jobByHold(locked.holdId()).status),
                "status=" + store.findOrderByHoldId(locked.holdId()).status()
                        + " job=" + store.jobByHold(locked.holdId()).status));

        LockNewResult cancel = occupy.lockNew(OccupyFixtures.cmd("rpt-cancel", OccupyFixtures.T1, 60));
        FireResult closed = machine.fire(cancel.orderId(), OrderEvent.USER_CANCEL);
        occupy.releaseLock(cancel.holdId());
        rows.add(new Row("LAW A", "fire 写 CLOSED 后 ReleaseLock；ReleaseLock 不再 fire",
                closed.to() == OrderStatus.CLOSED
                        && "CLOSED".equals(store.findOrderByHoldId(cancel.holdId()).status()),
                "to=" + closed.to()));

        boolean noSetStatus = noSetStatusInOrderPackage();
        rows.add(new Row("ARCH", "order 包无 setStatus", noSetStatus, "CAS via fire() only"));

        String html = render(rows, listedOk, illegal, illegalPass);
        Path docs = resolveRepoRoot().resolve("docs/test-cases");
        Files.createDirectories(docs);
        Path report = docs.resolve("pr-6-state-machine.html");
        Files.writeString(report, html, StandardCharsets.UTF_8);
        Files.createDirectories(resolveTargetDir());
        Files.writeString(resolveTargetDir().resolve("pr-6-state-machine.html"), html, StandardCharsets.UTF_8);

        List<Row> failed = rows.stream().filter(r -> !r.pass).toList();
        assertThat(failed).as("pr-6 report failures: %s", failed).isEmpty();
        assertThat(html).contains("40904").contains("PENDING_PAY").contains("Law A");
        assertThat(report).exists();
    }

    private static boolean noSetStatusInOrderPackage() throws Exception {
        Path dir = Path.of("src/main/java/com/jisuodashi/order");
        if (!Files.isDirectory(dir)) {
            dir = Path.of("server/src/main/java/com/jisuodashi/order");
        }
        try (var walk = Files.walk(dir)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (Files.readString(file).contains("setStatus(")) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String sides(OrderTransition t) {
        return t.sides().isEmpty() ? "—" : t.sides().toString();
    }

    private static String render(List<Row> rows, int listedOk, int illegal, int illegalPass) {
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
                  <title>PR6 订单状态机 · 闭合转移表</title>
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
                    .diagram { background:#fff; padding:20px 16px 8px; border-radius:8px;
                               box-shadow:0 1px 3px rgba(20,53,44,.06); overflow-x:auto; }
                    .row { display:flex; gap:10px; justify-content:center; flex-wrap:wrap;
                           margin-bottom:8px; align-items:center; }
                    .st { min-width:110px; text-align:center; padding:10px 8px; border-radius:8px;
                          font-size:12px; font-weight:700; border:2px solid var(--brand); background:#e8f1ed; }
                    .st.start { background:#1E5C4A; color:#fff; }
                    .st.end { background:#2fbf71; color:#062014; border-color:#2fbf71; }
                    .st.bad { background:#fdecea; border-color:#e85d4c; color:#8a2418; }
                    .st.closed { background:#dfe6e3; border-color:#8aa197; }
                    .arr { font-size:11px; color:#4a6a5f; text-align:center; min-width:90px; }
                    code { font-family: ui-monospace, Menlo, monospace; font-size:12px; }
                    footer { padding:0 40px 32px; color:#6b7c75; font-size:12px; }
                  </style>
                </head>
                <body>
                  <header>
                    <h1>PR6 · 闭合订单状态机 + POST /c/bookings</h1>
                    <p>表驱动 fire(from,event) · 未列出 40904 · Law A：fire 调 Release*，Release* 禁止 fire</p>
                    <span class="badge __CLS__">__BADGE__ · __OK__ / __N__</span>
                  </header>
                  <main>
                    <div class="diagram">
                      <div class="row">
                        <div class="st start">lockNew</div>
                        <div class="arr">→</div>
                        <div class="st">PENDING_PAY</div>
                        <div class="arr">PAY_SUCCESS</div>
                        <div class="st">BOOKED</div>
                        <div class="arr">CHECK_IN</div>
                        <div class="st">CHECKED_IN</div>
                      </div>
                      <div class="row">
                        <div class="arr">PAY_TIMEOUT / USER_CANCEL</div>
                        <div class="st closed">CLOSED</div>
                        <div class="arr">START_SERVICE</div>
                        <div class="st">IN_SERVICE</div>
                        <div class="arr">COMPLETE_SERVICE</div>
                        <div class="st end">COMPLETED</div>
                      </div>
                      <div class="row">
                        <div class="st">BOOKED</div>
                        <div class="arr">MARK_NO_SHOW</div>
                        <div class="st closed">NO_SHOW</div>
                        <div class="arr">CANCEL / REFUND</div>
                        <div class="st closed">CANCELLED</div>
                        <div class="arr">ABORT</div>
                        <div class="st bad">ABNORMAL</div>
                      </div>
                      <div class="row">
                        <div class="arr">RESOLVE_COMPLETE</div>
                        <div class="st end">COMPLETED</div>
                        <div class="arr">RESOLVE_CANCEL</div>
                        <div class="st closed">CANCELLED</div>
                        <div class="arr">REVIEW (P1)</div>
                        <div class="st end">REVIEWED</div>
                      </div>
                    </div>
                    <div class="callout">
                      <strong>Law A / D25</strong>
                      <div><code>fire()</code> 先 CAS 写目标状态，再调 <code>Release*</code> / <code>confirmPaidSlots</code>。
                      <code>Release*</code> 禁止再 <code>fire</code>。未列出的 <code>(from,event)</code> → <code>40904</code>。</div>
                    </div>
                    <div class="callout">
                      <strong>转移表穷举</strong>
                      <div>列出 __LISTED__ 条合法转移全部命中；未列出 __ILLEGAL__ 对全部 40904（__ILLEGAL_OK__）。</div>
                    </div>
                    <h2>验收项</h2>
                    <table>
                      <thead><tr><th>类</th><th>检查</th><th>细节</th><th>结果</th></tr></thead>
                      <tbody>
                __ROWS__      </tbody>
                    </table>
                  </main>
                  <footer>Generated by OrderStateMachineReportTest · D8 闭合表 · D25 Law A</footer>
                </body>
                </html>
                """
                .replace("__CLS__", ok == rows.size() ? "" : "fail")
                .replace("__BADGE__", badge)
                .replace("__OK__", String.valueOf(ok))
                .replace("__N__", String.valueOf(rows.size()))
                .replace("__LISTED__", String.valueOf(listedOk))
                .replace("__ILLEGAL__", String.valueOf(illegal))
                .replace("__ILLEGAL_OK__", String.valueOf(illegalPass))
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
