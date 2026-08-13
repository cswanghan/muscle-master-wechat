package com.jisuodashi;

import com.jisuodashi.catalog.CatalogDtos;
import com.jisuodashi.catalog.CatalogModels;
import com.jisuodashi.catalog.CatalogService;
import com.jisuodashi.catalog.DemoCatalogIds;
import com.jisuodashi.catalog.InMemoryCatalogRepository;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.FeatureFlags;
import com.jisuodashi.common.GrayStores;
import com.jisuodashi.common.PhoneCrypto;
import com.jisuodashi.frontdesk.FrontDeskDtos;
import com.jisuodashi.frontdesk.UtilizationService;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.inventory.InMemoryTherapistDayLock;
import com.jisuodashi.inventory.InventoryDriftGauge;
import com.jisuodashi.inventory.LockNewCommand;
import com.jisuodashi.inventory.OccupyFixtures;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.inventory.SlotOccupyStore.OccupancyInsert;
import com.jisuodashi.inventory.SlotOccupyStore.SlotRow;
import com.jisuodashi.inventory.SlotStatus;
import com.jisuodashi.inventory.ResourceType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpsReportTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 14);

    @Test
    void writeHtmlReportAndScreenshot() throws Exception {
        List<Row> rows = new ArrayList<>();

        List<SlotRow> mixed = List.of(
                new SlotRow(40, SlotStatus.BOOKED),
                new SlotRow(41, SlotStatus.FREE),
                new SlotRow(42, SlotStatus.REST),
                new SlotRow(43, SlotStatus.LOCKED),
                new SlotRow(44, SlotStatus.BUFFER),
                new SlotRow(45, SlotStatus.FREE),
                new SlotRow(46, SlotStatus.FREE),
                new SlotRow(47, SlotStatus.FREE));
        FrontDeskDtos.UtilizationResponse util = UtilizationService.compute(DemoCatalogIds.STORE, DAY, mixed);
        rows.add(row("UTIL", "全日 + byHour 满班率，REST 不进分母，BUFFER 算占用",
                util.rateX10000() != null
                        && util.rateX10000() == 3 * 10_000 / 7
                        && util.byHour().size() == 2
                        && util.byHour().get(0).rateX10000() == 2 * 10_000 / 3
                        && util.byHour().get(1).rateX10000() == 2500,
                "rate=" + util.rateX10000() + " hours=" + util.byHour().size()));

        FrontDeskDtos.UtilizationResponse empty = UtilizationService.compute(
                DemoCatalogIds.STORE, LocalDate.of(2020, 1, 1), List.of());
        rows.add(row("EMPTY", "空日 rateX10000=null 且仍返回 byHour=[]",
                empty.rateX10000() == null && empty.byHour().isEmpty(),
                "rate=null byHour=0"));

        AppProperties props = new AppProperties();
        props.getCrypto().setPhonePepper("dev-phone-pepper");
        props.getCrypto().setDekBase64("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        GrayStores gray = new GrayStores(props);
        CatalogService catalog = new CatalogService(
                new InMemoryCatalogRepository(), new PhoneCrypto(props), Clock.systemUTC(), props);
        catalog.setGrayStores(gray);
        CatalogDtos.Page<CatalogDtos.StoreListItem> listed = catalog.listStores(null, null, null, 20);
        boolean hidden = listed.items().stream()
                .noneMatch(s -> String.valueOf(DemoCatalogIds.STORE_EAST).equals(s.storeId()));
        boolean shown = listed.items().stream()
                .anyMatch(s -> String.valueOf(DemoCatalogIds.STORE).equals(s.storeId()));
        boolean notFound = false;
        try {
            catalog.getStore(DemoCatalogIds.STORE_EAST);
        } catch (ApiException ex) {
            notFound = ex.getCode() == ErrorCodes.NOT_FOUND;
        }
        rows.add(row("GRAY", "C 端列表只回灰度店；非灰度 GET 40401",
                shown && hidden && notFound && listed.items().size() == 1,
                "list=" + listed.items().size() + " east40401=" + notFound));

        CatalogModels.Therapist visitor = new InMemoryCatalogRepository().listTherapists().getFirst();
        rows.add(row("VISIT", "过滤键是 slot.store_id / store.id，不是 home_store_id",
                visitor.homeStoreId() == DemoCatalogIds.STORE && gray.allows(DemoCatalogIds.STORE)
                        && !gray.allows(DemoCatalogIds.STORE_EAST),
                "home=" + visitor.homeStoreId() + " eastAllowed=" + gray.allows(DemoCatalogIds.STORE_EAST)));

        AppClock clock = new AppClock(Clock.fixed(
                DAY.atTime(LocalTime.of(19, 0)).atZone(AppClock.SHANGHAI).toInstant(), AppClock.SHANGHAI));
        FeatureFlags flags = new FeatureFlags(props, clock);
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService occupy = new SlotOccupyService(
                store, new InMemoryTherapistDayLock(), new AtomicLong(9_100_000_000_000_000_000L)::incrementAndGet, clock);
        occupy.setFeatureFlags(flags);
        props.getFlags().getBooking().getLock().setEnabled(false);
        flags.refresh();
        boolean lock403 = false;
        try {
            occupy.lockNew(OccupyFixtures.cmd("rpt-lock-off", OccupyFixtures.T1, 64));
        } catch (ApiException ex) {
            lock403 = ex.getCode() == ErrorCodes.FORBIDDEN;
        }
        rows.add(row("LOCK", "booking.lock.enabled=false → lockNew/book 403",
                lock403, "forbidden=" + lock403));

        props.getFlags().getBooking().getLock().setEnabled(true);
        flags.refresh();
        occupy.lockNew(OccupyFixtures.cmd("rpt-lock-on", OccupyFixtures.T1, 64));
        rows.add(row("LOCKON", "开关打开后 lockNew 成功",
                store.occupancyCount() > 0, "occ=" + store.occupancyCount()));

        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        InventoryDriftGauge gauge = new InventoryDriftGauge(store, clock, meters);
        int before = gauge.scrape();
        store.beginWork();
        store.insertOccupancy(new OccupancyInsert(
                77L, ResourceType.THERAPIST, OccupyFixtures.T1, DAY, 80, 2L, 2L,
                LocalDateTime.of(DAY, LocalTime.NOON)));
        store.commitWork();
        int after = gauge.scrape();
        rows.add(row("DRIFT", "occupancy vs FREE 失配使 inventory.drift +1（60s 刮取）",
                after == before + 1
                        && meters.get(InventoryDriftGauge.METRIC).gauge().value() == after,
                "before=" + before + " after=" + after));

        String html = render(rows, util);
        Path docs = resolveRepoRoot().resolve("docs/test-cases");
        Files.createDirectories(docs);
        Path report = docs.resolve("pr-17-ops.html");
        Files.writeString(report, html, StandardCharsets.UTF_8);
        Files.createDirectories(resolveTargetDir());
        Files.writeString(resolveTargetDir().resolve("pr-17-ops.html"), html, StandardCharsets.UTF_8);

        Path shotDir = docs.resolve("screenshots");
        Files.createDirectories(shotDir);
        Path shot = shotDir.resolve("pr-17-ops.png");
        capture(report, shot);

        List<Row> failed = rows.stream().filter(r -> !r.pass).toList();
        assertThat(failed).as("pr-17 report failures: %s", failed).isEmpty();
        assertThat(html).contains("rateX10000").contains("inventory.drift").contains("40401")
                .contains("booking.lock.enabled").contains("byHour");
        assertThat(report).exists();
        assertThat(shot).exists();
    }

    private static Row row(String kind, String check, boolean pass, String detail) {
        return new Row(kind, check, pass, detail);
    }

    private static String render(List<Row> rows, FrontDeskDtos.UtilizationResponse util) {
        long ok = rows.stream().filter(r -> r.pass).count();
        StringBuilder body = new StringBuilder();
        for (Row row : rows) {
            body.append("<tr class='").append(row.pass ? "ok" : "bad").append("'>")
                    .append("<td>").append(esc(row.kind)).append("</td>")
                    .append("<td>").append(esc(row.check)).append("</td>")
                    .append("<td>").append(esc(row.detail)).append("</td>")
                    .append("<td>").append(row.pass ? "PASS" : "FAIL").append("</td></tr>\n");
        }
        StringBuilder hours = new StringBuilder();
        for (FrontDeskDtos.HourUtilization h : util.byHour()) {
            hours.append("<div class='hour'><b>").append(h.hour()).append(":00</b><span>")
                    .append(h.rateX10000() == null ? "—" : (h.rateX10000() / 100.0) + "%")
                    .append("</span></div>");
        }
        String badge = ok == rows.size() ? "ALL PASS" : "FAIL";
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                  <meta charset="UTF-8"/>
                  <title>PR17 满班率 · 漂移 · 灰度</title>
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
                    .st.bad { background:#fdecea; border-color:#e85d4c; color:#8a2418; }
                    .arr { font-size:12px; color:#4a6a5f; }
                    .desk { width:1024px; max-width:100%; background:var(--paper); border:10px solid #1a1c1b;
                            border-radius:18px; padding:20px; box-sizing:border-box; }
                    .bar { display:flex; justify-content:space-between; align-items:center; margin-bottom:16px; }
                    .bar h3 { margin:0; color:var(--brand); }
                    .rate { font-size:22px; font-weight:700; color:var(--brand); text-align:right; }
                    .rate small { display:block; font-size:12px; font-weight:400; color:#4a5a54; }
                    .hours { display:flex; gap:8px; flex-wrap:wrap; }
                    .hour { background:#fff; border:1px solid #d7e6df; border-radius:8px; padding:8px 12px;
                            min-width:72px; }
                    .hour b { display:block; color:var(--brand); }
                    code { font-family: ui-monospace, Menlo, monospace; font-size:12px; }
                    footer { padding:0 40px 32px; color:#6b7c75; font-size:12px; }
                  </style>
                </head>
                <body>
                  <header>
                    <h1>PR17 · 满班率 + inventory.drift + 灰度 / 开关</h1>
                    <p>GET /f/metrics/utilization · Prometheus 60s · app.gray.store-ids · app.flags</p>
                    <span class="badge __CLS__">__BADGE__ · __OK__ / __N__</span>
                  </header>
                  <main>
                    <div class="flow">
                      <div class="st">therapist_slot.store_id</div>
                      <div class="arr">BOOKED+BUFFER+LOCKED / ≠REST</div>
                      <div class="st pay">rateX10000 + byHour</div>
                    </div>
                    <div class="flow">
                      <div class="st">occupancy XOR LOCKED/BOOKED/BUFFER</div>
                      <div class="arr">60s scrape</div>
                      <div class="st pay">inventory.drift</div>
                    </div>
                    <div class="flow">
                      <div class="st">app.gray.store-ids</div>
                      <div class="arr">非灰度</div>
                      <div class="st bad">40401</div>
                      <div class="arr">booking.lock=false</div>
                      <div class="st bad">403 lockNew/book</div>
                    </div>
                    <div class="callout">
                      <strong>M1 / 前台顶栏</strong>
                      <div>分母不含 REST；BUFFER 算占用。跨店支援按 slot 店。0 分母 → <code>null</code>。
                      不要缩成单数字，始终带 <code>byHour</code>。
                      漂移禁止 15s 打热表。C 端门店列表只回灰度店。</div>
                    </div>
                    <h2>iPad 前台顶栏 · 满班率</h2>
                    <div class="desk" id="desk-utilization">
                      <div class="bar">
                        <h3>门店前台</h3>
                        <div class="rate">满班率 __RATE__
                          <small>2026-08-14 · byHour</small>
                        </div>
                      </div>
                      <div class="hours">__HOURS__</div>
                    </div>
                    <h2>验收项</h2>
                    <table>
                      <thead><tr><th>类</th><th>检查</th><th>细节</th><th>结果</th></tr></thead>
                      <tbody>
                __ROWS__      </tbody>
                    </table>
                  </main>
                  <footer>Generated by OpsReportTest · GET /f/metrics/utilization · inventory.drift · gray.store-ids</footer>
                </body>
                </html>
                """
                .replace("__CLS__", ok == rows.size() ? "" : "fail")
                .replace("__BADGE__", badge)
                .replace("__OK__", String.valueOf(ok))
                .replace("__N__", String.valueOf(rows.size()))
                .replace("__ROWS__", body)
                .replace("__RATE__", util.rateX10000() == null ? "—" : (util.rateX10000() / 100.0) + "%")
                .replace("__HOURS__", hours);
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

    private static Path resolveRepoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6; i++) {
            if (Files.isDirectory(dir.resolve("docs/test-cases"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return Path.of("").toAbsolutePath();
    }

    private static Path resolveTargetDir() {
        Path cwd = Path.of("").toAbsolutePath();
        if (cwd.endsWith("server")) {
            return cwd.resolve("target");
        }
        return cwd.resolve("server/target");
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private record Row(String kind, String check, boolean pass, String detail) {
    }
}
