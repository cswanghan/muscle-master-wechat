package com.jisuodashi.inventory;

import com.jisuodashi.common.ApiException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.jisuodashi.inventory.OccupyFixtures.BED2;
import static com.jisuodashi.inventory.OccupyFixtures.START_1930;
import static com.jisuodashi.inventory.OccupyFixtures.T1;
import static com.jisuodashi.inventory.OccupyFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;

class RescheduleReportTest {

    static final long T2 = OccupyFixtures.T2;

    @Test
    void writesRescheduleReportHtml() throws Exception {
        InMemorySlotOccupyStore shiftStore = OccupyFixtures.demoStore();
        SlotOccupyService shiftSvc = OccupyFixtures.service(shiftStore);
        long shiftId = bookPaid(shiftStore, shiftSvc, "rpt-shift", T1, START_1930);
        long shiftOld = shiftStore.findOrderById(shiftId).holdId();
        RescheduleResult shift = shiftSvc.reschedule(
                new RescheduleCommand("rpt-shift-1", shiftId, TODAY, 80, T1, 1L));

        InMemorySlotOccupyStore swapStore = OccupyFixtures.demoStore();
        SlotOccupyService swapSvc = OccupyFixtures.service(swapStore);
        bookPaid(swapStore, swapSvc, "rpt-bed1", T1, START_1930);
        long swapId = bookPaid(swapStore, swapSvc, "rpt-swap", T2, START_1930);
        long originalBed = swapStore.findOrderById(swapId).bedId();
        RescheduleResult swap = swapSvc.reschedule(
                new RescheduleCommand("rpt-swap-1", swapId, TODAY, 64, T1, 1L));

        InMemorySlotOccupyStore busyStore = OccupyFixtures.demoStore();
        SlotOccupyService busySvc = OccupyFixtures.service(busyStore);
        long busyId = bookPaid(busyStore, busySvc, "rpt-busy-src", T1, 64);
        bookPaid(busyStore, busySvc, "rpt-busy-dst", T2, START_1930);
        busyStore.therapistSlot(T1, TODAY, 80).status = SlotStatus.BOOKED;
        int occBusy = busyStore.occupancyCount();
        int codeBusy = 0;
        try {
            busySvc.reschedule(new RescheduleCommand("rpt-busy-1", busyId, TODAY, START_1930, T1, 1L));
        } catch (ApiException ex) {
            codeBusy = ex.getCode();
        }

        InMemorySlotOccupyStore statusStore = OccupyFixtures.demoStore();
        SlotOccupyService statusSvc = OccupyFixtures.service(statusStore);
        LockNewResult pending = statusSvc.lockNew(OccupyFixtures.cmd("rpt-pend", T1, START_1930));
        int codePending = 0;
        try {
            statusSvc.reschedule(new RescheduleCommand("rpt-pend-1", pending.orderId(), TODAY, 64, T1, 1L));
        } catch (ApiException ex) {
            codePending = ex.getCode();
        }

        boolean shiftOk = shift.acquireCount() == 4 && shift.releaseCount() == 4 && shift.keepCount() == 6
                && shift.holdId() != shiftOld
                && shiftStore.occupancyCount() == 10
                && shiftStore.occupancies.values().stream().allMatch(o -> o.holdId() == shift.holdId());
        boolean swapOk = swap.therapistId() == T1 && swap.bedId() == originalBed && originalBed == BED2
                && SlotStatus.FREE.equals(swapStore.therapistSlot(T2, TODAY, 78).status);
        boolean busyOk = codeBusy == 40901 && busyStore.occupancyCount() == occBusy
                && busyStore.findOrderById(busyId).startSlotNo() == 64;
        boolean statusOk = codePending == 40904 && statusStore.findOrderById(pending.orderId()).startSlotNo() == START_1930;

        String html = render(shiftOk, swapOk, busyOk, statusOk, shift, swap, codeBusy, codePending);
        Path docs = resolveRepoRoot().resolve("docs/test-cases");
        Files.createDirectories(docs);
        Path report = docs.resolve("pr-15-reschedule.html");
        Files.writeString(report, html, StandardCharsets.UTF_8);
        Files.createDirectories(resolveTargetDir());
        Files.writeString(resolveTargetDir().resolve("pr-15-reschedule.html"), html, StandardCharsets.UTF_8);

        assertThat(shiftOk && swapOk && busyOk && statusOk).as("pr-15 report rows").isTrue();
        assertThat(report).exists();
        assertThat(html).contains("RESCHEDULE").contains("集合差").contains("40901").contains("40904");
    }

    private static long bookPaid(
            InMemorySlotOccupyStore store, SlotOccupyService service, String requestId, long therapist, int start) {
        LockNewResult locked = service.lockNew(OccupyFixtures.cmd(requestId, therapist, start));
        service.confirmPaidSlots(locked.orderId());
        store.setOrderStatus(locked.orderId(), SlotOccupyService.ORDER_BOOKED);
        return locked.orderId();
    }

    private static String render(
            boolean shiftOk, boolean swapOk, boolean busyOk, boolean statusOk,
            RescheduleResult shift, RescheduleResult swap, int codeBusy, int codePending) {
        long ok = (shiftOk ? 1 : 0) + (swapOk ? 1 : 0) + (busyOk ? 1 : 0) + (statusOk ? 1 : 0);
        String badge = ok == 4 ? "ALL PASS" : "FAIL";
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                  <meta charset="UTF-8"/>
                  <title>PR15 前台改约 · 集合差</title>
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
                    .arr { font-size:12px; color:#4a6a5f; }
                    .ipad { width:1024px; max-width:100%; background:var(--paper); border:10px solid #1a1c1b;
                            border-radius:18px; padding:20px; display:grid; grid-template-columns:1fr 1fr;
                            gap:16px; box-sizing:border-box; }
                    .pad { background:#fff; border-radius:12px; padding:16px 18px; min-height:220px; }
                    .pad h3 { margin:0 0 12px; color:var(--brand); font-size:18px; }
                    .field { margin:10px 0; }
                    .field label { display:block; font-size:15px; color:#4a5a54; margin-bottom:4px; }
                    .fake { height:48px; border:1px solid #d7e6df; border-radius:8px; background:#f8faf9;
                            display:flex; align-items:center; padding:0 12px; font-size:16px; }
                    .btn { height:48px; border:none; border-radius:8px; background:var(--brand); color:#fff;
                           font-size:16px; font-weight:700; padding:0 20px; }
                    .hint { font-size:15px; color:#4a6a5f; }
                    code { font-family: ui-monospace, Menlo, monospace; font-size:12px; }
                    footer { padding:0 40px 32px; color:#6b7c75; font-size:12px; }
                  </style>
                </head>
                <body>
                  <header>
                    <h1>PR15 · 前台改约集合差</h1>
                    <p>POST /f/orders/{id}/reschedule · occupy 不 fire · 已支付不建 RELEASE_LOCK · 无 C 端</p>
                    <span class="badge __CLS__">__BADGE__ · __OK__ / 4</span>
                  </header>
                  <main>
                    <div class="flow">
                      <div class="st">BOOKED</div>
                      <div class="arr">acquire ∪ release ∪ keep FOR UPDATE</div>
                      <div class="st pay">reschedule()</div>
                      <div class="arr">fire(RESCHEDULE, rescheduleOk)</div>
                      <div class="st pay">BOOKED</div>
                    </div>
                    <div class="callout">
                      <strong>§2.4.4</strong>
                      <div>交集只 UPDATE <code>hold_id</code>，禁止 INSERT keep。目标忙 40901/40902；
                      非 BOOKED 40904。同店同项目同价。优先原床。</div>
                    </div>
                    <h2>iPad 前台 1024（改约）</h2>
                    <div class="ipad" id="ipad-desk">
                      <section class="pad">
                        <h3>到店核销</h3>
                        <div class="field"><label>单号 / 手机</label>
                          <div class="fake">JS20260814… / 186****1111</div></div>
                        <button class="btn">核销到店</button>
                      </section>
                      <section class="pad">
                        <h3>改约</h3>
                        <div class="field"><label>日期 / 起始格</label>
                          <div class="fake">2026-08-14 · slot 72</div></div>
                        <div class="field"><label>技师</label>
                          <div class="fake">林晓 → 陈默</div></div>
                        <button class="btn">确认改约</button>
                        <p class="hint">BOOKED · 同店同项目同价 · 无 C 端</p>
                      </section>
                    </div>
                    <h2>验收项</h2>
                    <table>
                      <thead><tr><th>类</th><th>检查</th><th>细节</th><th>结果</th></tr></thead>
                      <tbody>
                        <tr class="__S__"><td>SHIFT</td><td>同技师平移：acquire/release/keep</td>
                          <td>acq=__ACQ__ rel=__REL__ keep=__KEEP__ hold 更新 occupancy=10</td>
                          <td>__SP__</td></tr>
                        <tr class="__W__"><td>SWAP</td><td>换技师锁新放旧，床优先原床</td>
                          <td>newT=__NT__ bed=__BED__</td>
                          <td>__WP__</td></tr>
                        <tr class="__B__"><td>BUSY</td><td>目标忙 40901，原占用不动</td>
                          <td>code=__BC__</td>
                          <td>__BP__</td></tr>
                        <tr class="__N__"><td>STATUS</td><td>非 BOOKED → 40904</td>
                          <td>code=__NC__</td>
                          <td>__NP__</td></tr>
                      </tbody>
                    </table>
                  </main>
                  <footer>Generated by RescheduleReportTest · Law A occupy 不 fire · uk_occ 不插 keep</footer>
                </body>
                </html>
                """
                .replace("__CLS__", ok == 4 ? "" : "fail")
                .replace("__BADGE__", badge)
                .replace("__OK__", String.valueOf(ok))
                .replace("__S__", shiftOk ? "ok" : "bad")
                .replace("__W__", swapOk ? "ok" : "bad")
                .replace("__B__", busyOk ? "ok" : "bad")
                .replace("__N__", statusOk ? "ok" : "bad")
                .replace("__SP__", shiftOk ? "PASS" : "FAIL")
                .replace("__WP__", swapOk ? "PASS" : "FAIL")
                .replace("__BP__", busyOk ? "PASS" : "FAIL")
                .replace("__NP__", statusOk ? "PASS" : "FAIL")
                .replace("__ACQ__", String.valueOf(shift.acquireCount()))
                .replace("__REL__", String.valueOf(shift.releaseCount()))
                .replace("__KEEP__", String.valueOf(shift.keepCount()))
                .replace("__NT__", String.valueOf(swap.therapistId()))
                .replace("__BED__", String.valueOf(swap.bedId()))
                .replace("__BC__", String.valueOf(codeBusy))
                .replace("__NC__", String.valueOf(codePending));
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
}
