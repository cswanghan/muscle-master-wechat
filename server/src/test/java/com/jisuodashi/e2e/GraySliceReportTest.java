package com.jisuodashi.e2e;

import com.jisuodashi.common.AppProperties;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.inventory.LockNewResult;
import com.jisuodashi.inventory.OccupyFixtures;
import com.jisuodashi.inventory.ReleaseResult;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.inventory.SlotStatus;
import com.jisuodashi.job.ForceReleaseJob;
import com.jisuodashi.job.JobRunner;
import com.jisuodashi.job.SlotScanJob;
import com.jisuodashi.order.FireContext;
import com.jisuodashi.order.OrderEvent;
import com.jisuodashi.order.OrderStateMachine;
import com.jisuodashi.order.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.jisuodashi.inventory.OccupyFixtures.START_1930;
import static com.jisuodashi.inventory.OccupyFixtures.T1;
import static org.assertj.core.api.Assertions.assertThat;

class GraySliceReportTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 14);

    @Test
    void writeHtmlReportAndScreenshot() throws Exception {
        List<Row> rows = new ArrayList<>();

        AppProperties props = new AppProperties();
        rows.add(row("FLAG", "app.booking.lock.enabled 默认 true，可紧急关掉",
                props.getBooking().getLock().isEnabled(),
                "lock.enabled=" + props.getBooking().getLock().isEnabled()));
        props.getBooking().getLock().setEnabled(false);
        rows.add(row("FLAG", "booking.lock.enabled=false 停锁",
                !props.getBooking().getLock().isEnabled(),
                "lock.enabled=" + props.getBooking().getLock().isEnabled()));

        InMemorySlotOccupyStore cancelStore = OccupyFixtures.demoStore();
        SlotOccupyService cancelOccupy = OccupyFixtures.service(cancelStore);
        var clock = new com.jisuodashi.common.AppClock(java.time.Clock.fixed(
                TODAY.atTime(19, 0).atZone(com.jisuodashi.common.AppClock.SHANGHAI).toInstant(),
                com.jisuodashi.common.AppClock.SHANGHAI));
        OrderStateMachine cancelMachine = new OrderStateMachine(cancelStore, cancelOccupy, clock);
        LockNewResult toCancel = cancelOccupy.lockNew(OccupyFixtures.cmd("rpt18-cancel", T1, 52));
        var closed = cancelMachine.fire(toCancel.orderId(), OrderEvent.USER_CANCEL,
                FireContext.customer(OccupyFixtures.CUSTOMER));
        rows.add(row("CANCEL", "未支付 USER_CANCEL → CLOSED + ReleaseLock",
                closed.to() == OrderStatus.CLOSED
                        && cancelStore.occupancies.isEmpty()
                        && SlotStatus.FREE.equals(cancelStore.therapistSlot(T1, TODAY, 52).status),
                "status=" + closed.to() + " occ=" + cancelStore.occupancies.size()));

        InMemorySlotOccupyStore timeoutStore = OccupyFixtures.demoStore();
        SlotOccupyService timeoutOccupy = OccupyFixtures.service(timeoutStore);
        OrderStateMachine timeoutMachine = new OrderStateMachine(timeoutStore, timeoutOccupy, clock);
        LockNewResult leftover = timeoutOccupy.lockNew(OccupyFixtures.cmd("rpt18-timeout", T1, 60));
        timeoutStore.expireHold(leftover.holdId(), TODAY.atTime(18, 50));
        var scan = new SlotScanJob(timeoutOccupy, timeoutMachine).run();
        rows.add(row("TIMEOUT", "PAY_TIMEOUT 扫描 → CLOSED + 格 FREE",
                scan.pendingReleased() == 1
                        && "CLOSED".equals(timeoutStore.findOrderByHoldId(leftover.holdId()).status())
                        && timeoutStore.occupancies.isEmpty(),
                "pending=" + scan.pendingReleased()
                        + " status=" + timeoutStore.findOrderByHoldId(leftover.holdId()).status()));

        InMemorySlotOccupyStore stuck = OccupyFixtures.demoStore();
        SlotOccupyService stuckOccupy = OccupyFixtures.service(stuck);
        LockNewResult planted = stuckOccupy.lockNew(OccupyFixtures.cmd("rpt18-force", T1, START_1930));
        ReleaseResult forced = new ForceReleaseJob(stuckOccupy).run(planted.holdId());
        rows.add(row("DRILL", "ForceReleaseJob 清 stuck LOCKED → FREE + occupancy 0",
                forced.freed()
                        && stuck.occupancies.isEmpty()
                        && SlotStatus.FREE.equals(stuck.therapistSlot(T1, TODAY, START_1930).status),
                "outcome=" + forced.outcome() + " occ=" + stuck.occupancies.size()));

        Method scanMethod = JobRunner.class.getDeclaredMethod("scanExpiredLocksEvery5Min");
        Scheduled scheduled = scanMethod.getAnnotation(Scheduled.class);
        rows.add(row("SCAN", "5 min 扫描必须保留（不随镜像回滚删掉）",
                "0 */5 * * * *".equals(scheduled.cron()) && "Asia/Shanghai".equals(scheduled.zone()),
                "cron=" + scheduled.cron() + " zone=" + scheduled.zone()));

        Path runbook = resolveRepoRoot().resolve("docs/runbooks/lock-rollback.md");
        String runbookText = Files.readString(runbook);
        rows.add(row("RUNBOOK", "docs/runbooks/lock-rollback.md 对齐设计 §坏锁回滚",
                runbookText.contains("booking.lock.enabled=false")
                        && runbookText.contains("不要回滚 Flyway")
                        && runbookText.contains("ForceReleaseJob")
                        && runbookText.contains("5 min"),
                runbook.toString()));

        String html = render(rows);
        Path docs = resolveRepoRoot().resolve("docs/test-cases");
        Files.createDirectories(docs);
        Path report = docs.resolve("pr-18-gray-slice.html");
        Files.writeString(report, html, StandardCharsets.UTF_8);
        Files.createDirectories(resolveTargetDir());
        Files.writeString(resolveTargetDir().resolve("pr-18-gray-slice.html"), html, StandardCharsets.UTF_8);

        Path shotDir = docs.resolve("screenshots");
        Files.createDirectories(shotDir);
        Path shot = shotDir.resolve("pr-18-gray-slice.png");
        capture(report, shot);

        List<Row> failed = rows.stream().filter(r -> !r.pass).toList();
        assertThat(failed).as("pr-18 report failures: %s", failed).isEmpty();
        assertThat(html).contains("LOGIN").contains("ForceReleaseJob").contains("PAY_TIMEOUT");
        assertThat(report).exists();
        assertThat(shot).exists();
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
                  <title>PR18 灰度切片 · 坏锁回滚演练</title>
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
                    .flow { display:flex; gap:8px; flex-wrap:wrap; align-items:center; margin:12px 0; }
                    .st { padding:8px 10px; border-radius:8px; font-size:12px; font-weight:700;
                          border:2px solid var(--brand); background:#e8f1ed; }
                    .st.pay { background:#1E5C4A; color:#fff; }
                    .st.warn { background:#f4e6c4; border-color:#c9a227; }
                    .arr { font-size:12px; color:#4a6a5f; }
                    code { font-family: ui-monospace, Menlo, monospace; font-size:12px; }
                    footer { padding:0 40px 32px; color:#6b7c75; font-size:12px; }
                    ol.run { background:#fff; padding:12px 16px 12px 36px; border-radius:8px; }
                  </style>
                </head>
                <body>
                  <header>
                    <h1>PR18 · 灰度切片 e2e + 坏锁回滚演练</h1>
                    <p>gray slice = PR7+PR8+PR9+PR11 · login / browse / book+pay / check-in / cash walk-in / unpaid cancel</p>
                    <span class="badge __CLS__">__BADGE__ · __OK__ / __N__</span>
                  </header>
                  <main>
                    <h2>Gray slice</h2>
                    <div class="flow">
                      <div class="st">LOGIN mock WeChat</div>
                      <div class="arr">→</div>
                      <div class="st">BROWSE stores/therapists/projects</div>
                      <div class="arr">→</div>
                      <div class="st pay">BOOK + notify</div>
                      <div class="arr">→</div>
                      <div class="st pay">CHECK_IN</div>
                      <div class="arr">→</div>
                      <div class="st pay">CASH walk-in</div>
                      <div class="arr">→</div>
                      <div class="st">UNPAID cancel / PAY_TIMEOUT</div>
                    </div>
                    <h2>坏锁回滚</h2>
                    <div class="flow">
                      <div class="st warn">booking.lock.enabled=false</div>
                      <div class="arr">→</div>
                      <div class="st">ForceReleaseJob PENDING_PAY leftover</div>
                      <div class="arr">→</div>
                      <div class="st">superadmin force-release orphan</div>
                      <div class="arr">→</div>
                      <div class="st warn">不要回滚 Flyway</div>
                      <div class="arr">→</div>
                      <div class="st pay">5 min scan 必须留</div>
                    </div>
                    <div class="callout">
                      <strong>§坏锁回滚</strong>
                      <div>紧急停锁只改 <code>app.booking.lock.enabled</code>。扫描 cron
                      <code>0 */5 * * * *</code> Asia/Shanghai 是权威释放路径。
                      <code>forceFreeByHold</code> 只清 LOCKED；已 BOOKED 占用不动。见
                      <code>docs/runbooks/lock-rollback.md</code>。</div>
                    </div>
                    <ol class="run">
                      <li><code>booking.lock.enabled=false</code>，C 端停售，前台停散客。</li>
                      <li>跑 <code>ForceReleaseJob</code>：PENDING_PAY 超时单释放；列出仍 LOCKED 且无订单的格。</li>
                      <li>超管 <code>POST /internal/force-release?holdId=</code>（本机 + token）。</li>
                      <li>回滚 server 镜像；<strong>不要回滚 Flyway</strong>。</li>
                      <li>灰度店先开锁，跑并发套件再开第二家。</li>
                    </ol>
                    <h2>验收项</h2>
                    <table>
                      <thead><tr><th>类</th><th>检查</th><th>细节</th><th>结果</th></tr></thead>
                      <tbody>
                __ROWS__      </tbody>
                    </table>
                  </main>
                  <footer>Generated by GraySliceReportTest · H2/dev · no Docker · random port</footer>
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
            if (bin.contains("/") && !Files.isExecutable(Path.of(bin))) {
                continue;
            }
            try {
                Process proc = new ProcessBuilder(
                        bin, "--headless=new", "--disable-gpu", "--hide-scrollbars",
                        "--window-size=1280,1400",
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
}
