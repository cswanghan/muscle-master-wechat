package com.jisuodashi.inventory;

import com.jisuodashi.job.SlotScanJob;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static com.jisuodashi.inventory.OccupyFixtures.BED1;
import static com.jisuodashi.inventory.OccupyFixtures.BED2;
import static com.jisuodashi.inventory.OccupyFixtures.START_1930;
import static com.jisuodashi.inventory.OccupyFixtures.T1;
import static com.jisuodashi.inventory.OccupyFixtures.T2;
import static com.jisuodashi.inventory.OccupyFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;

class ReleaseLockReportTest {

    @Test
    void writesReleaseReportHtml() throws Exception {
        InMemorySlotOccupyStore timeoutStore = OccupyFixtures.demoStore();
        SlotOccupyService timeoutSvc = OccupyFixtures.service(timeoutStore);
        LockNewResult timeout = timeoutSvc.lockNew(OccupyFixtures.cmd("rpt-timeout", T1, START_1930));
        timeoutStore.expireHold(timeout.holdId(), TODAY.atTime(18, 50));
        SlotScanResult timeoutScan = new SlotScanJob(timeoutSvc).run();

        InMemorySlotOccupyStore paidStore = OccupyFixtures.demoStore();
        SlotOccupyService paidSvc = OccupyFixtures.service(paidStore);
        LockNewResult paid = paidSvc.lockNew(OccupyFixtures.cmd("rpt-paid", T1, START_1930));
        paidSvc.confirmPaidSlots(paid.orderId());
        paidStore.setOrderStatus(paid.orderId(), "BOOKED");
        paidStore.expireHold(paid.holdId(), TODAY.atTime(18, 50));
        SlotScanResult paidScan = new SlotScanJob(paidSvc).run();

        InMemorySlotOccupyStore orphanStore = OccupyFixtures.demoStore();
        long bedHold = 6_600_000_000_000_000_022L;
        plantBedOnly(orphanStore, BED1, bedHold, TODAY.atTime(18, 40));
        SlotScanResult bedOrphan = new SlotScanJob(OccupyFixtures.service(orphanStore)).run();

        InMemorySlotOccupyStore dualStore = OccupyFixtures.demoStore();
        long tHold = 6_600_000_000_000_000_031L;
        long bHold = 6_600_000_000_000_000_032L;
        plantTherapistOnly(dualStore, T2, tHold, TODAY.atTime(18, 40));
        plantBedOnly(dualStore, BED2, bHold, TODAY.atTime(18, 41));
        SlotScanResult dual = new SlotScanJob(OccupyFixtures.service(dualStore)).run();

        assertThat(timeoutScan.pendingReleased()).isEqualTo(1);
        assertThat(timeoutStore.occupancies).isEmpty();
        assertThat(paidScan.holdsSeen()).isZero();
        assertThat(paidStore.occupancies).hasSize(10);
        assertThat(bedOrphan.orphansFreed()).isEqualTo(1);
        assertThat(dual.holdIds()).containsExactly(tHold, bHold);
        assertThat(dual.orphansFreed()).isEqualTo(2);

        String html = render(timeoutScan, timeoutStore.occupancies.size(),
                paidScan, paidStore.occupancies.size(),
                bedOrphan, dual);
        Path docs = resolveRepoRoot().resolve("docs/test-cases");
        Files.createDirectories(docs);
        Path report = docs.resolve("pr-3c-release-report.html");
        Files.writeString(report, html, StandardCharsets.UTF_8);
        Files.createDirectories(resolveTargetDir());
        Files.writeString(resolveTargetDir().resolve("pr-3c-release-report.html"), html, StandardCharsets.UTF_8);
        assertThat(report).exists();
        assertThat(html).contains("timeout").contains("orphan").contains("dual-table");
    }

    private static void plantBedOnly(InMemorySlotOccupyStore store, long bedId, long holdId, LocalDateTime expire) {
        store.beginWork();
        for (int slot = 78; slot <= 82; slot++) {
            var row = store.bedSlot(bedId, TODAY, slot);
            row.status = SlotStatus.LOCKED;
            row.holdId = holdId;
            row.lockExpireAt = expire;
            store.insertOccupancy(new SlotOccupyStore.OccupancyInsert(
                    holdId + slot, ResourceType.BED, bedId, TODAY, slot, holdId, holdId, expire));
        }
        store.commitWork();
    }

    private static void plantTherapistOnly(
            InMemorySlotOccupyStore store, long therapistId, long holdId, LocalDateTime expire) {
        store.beginWork();
        for (int slot = 78; slot <= 82; slot++) {
            var row = store.therapistSlot(therapistId, TODAY, slot);
            row.status = SlotStatus.LOCKED;
            row.holdId = holdId;
            row.lockExpireAt = expire;
            store.insertOccupancy(new SlotOccupyStore.OccupancyInsert(
                    holdId + slot, ResourceType.THERAPIST, therapistId, TODAY, slot, holdId, holdId, expire));
        }
        store.commitWork();
    }

    private static String render(
            SlotScanResult timeout, int timeoutOcc,
            SlotScanResult paid, int paidOcc,
            SlotScanResult bedOrphan, SlotScanResult dual
    ) {
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                  <meta charset="UTF-8"/>
                  <title>PR3c ReleaseLock · dual-table scan</title>
                  <style>
                    :root { --ink:#14352c; --brand:#1E5C4A; --bg:#f4f7f5; --line:#d5e3dc; }
                    body { font-family: ui-sans-serif, "PingFang SC", sans-serif; margin:0;
                           background:var(--bg); color:var(--ink); }
                    header { background:var(--brand); color:#fff; padding:28px 40px; }
                    header h1 { margin:0 0 6px; font-size:22px; }
                    header p { margin:0; opacity:.85; }
                    .badge { display:inline-block; background:#2fbf71; color:#062014;
                             font-weight:700; padding:4px 12px; border-radius:6px; margin-top:10px; }
                    main { padding:28px 40px 48px; }
                    .meta { display:flex; gap:12px; flex-wrap:wrap; margin-bottom:20px; }
                    .pill { background:#fff; border:1px solid var(--line); border-radius:999px;
                            padding:6px 14px; font-size:13px; }
                    h2 { font-size:16px; color:var(--brand); margin:28px 0 12px; }
                    table { width:100%; border-collapse:collapse; background:#fff;
                            box-shadow:0 1px 3px rgba(20,53,44,.06); margin-bottom:20px; }
                    th, td { text-align:left; padding:8px 10px; border-bottom:1px solid var(--line);
                             font-size:13px; }
                    th { background:#e8f1ed; color:var(--brand); }
                    td.ok { color:#1E5C4A; font-weight:700; }
                    footer { padding:0 40px 32px; color:#6b7c75; font-size:12px; }
                    code { font-family: ui-monospace, Menlo, monospace; font-size:12px; }
                  </style>
                </head>
                <body>
                  <header>
                    <h1>ReleaseLock · 双表扫描 · ForceRelease</h1>
                    <p>Law A: Release* 禁止 fire() · PENDING_PAY + LOCKED 才释放 · 孤儿 forceFreeByHold</p>
                    <span class="badge">PASS · timeout freed · paid kept · bed orphan · dual-table</span>
                  </header>
                  <main>
                    <div class="meta">
                      <span class="pill">SlotScanJob 0 */5 * * * * Asia/Shanghai</span>
                      <span class="pill">UNION therapist_slot ∪ bed_slot LOCKED ∧ expire &lt; now</span>
                      <span class="pill">D16: 40904 → DONE · RELEASE_LOCK no-op until fire</span>
                      <span class="pill">confirmPaidSlots: BOOKED/BUFFER + job DONE</span>
                    </div>
                    <h2>闸门用例</h2>
                    <table>
                      <thead><tr><th>用例</th><th>scan holds</th><th>released</th><th>occupancy</th><th>期望</th></tr></thead>
                      <tbody>
                        <tr>
                          <td>timeout lock released</td>
                          <td class="ok">__TH__</td>
                          <td class="ok">pending=__TP__</td>
                          <td class="ok">__TOCC__</td>
                          <td>EXPIRED PENDING_PAY → FREE；订单仍 PENDING_PAY（不 fire）</td>
                        </tr>
                        <tr>
                          <td>paid order not released by expire job</td>
                          <td class="ok">__PH__</td>
                          <td class="ok">0</td>
                          <td class="ok">__POCC__</td>
                          <td>confirmPaid → BOOKED/BUFFER；occupancy 10；RELEASE_LOCK DONE</td>
                        </tr>
                        <tr>
                          <td>bed-only orphan released</td>
                          <td class="ok">__BH__</td>
                          <td class="ok">orphan=__BO__</td>
                          <td class="ok">0</td>
                          <td>仅 bed_slot LOCKED 且无订单 → forceFreeByHold</td>
                        </tr>
                        <tr>
                          <td>dual-table scan finds both</td>
                          <td class="ok">__DH__</td>
                          <td class="ok">orphan=__DO__</td>
                          <td class="ok">0</td>
                          <td>技师 hold + 床 hold 一次 UNION 都扫到</td>
                        </tr>
                      </tbody>
                    </table>
                  </main>
                  <footer>Generated by ReleaseLockReportTest with in-memory CAS (V3 IDs). Law A / D16 / D25.</footer>
                </body>
                </html>
                """
                .replace("__TH__", String.valueOf(timeout.holdsSeen()))
                .replace("__TP__", String.valueOf(timeout.pendingReleased()))
                .replace("__TOCC__", String.valueOf(timeoutOcc))
                .replace("__PH__", String.valueOf(paid.holdsSeen()))
                .replace("__POCC__", String.valueOf(paidOcc))
                .replace("__BH__", String.valueOf(bedOrphan.holdsSeen()))
                .replace("__BO__", String.valueOf(bedOrphan.orphansFreed()))
                .replace("__DH__", String.valueOf(dual.holdsSeen()))
                .replace("__DO__", String.valueOf(dual.orphansFreed()));
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
