package com.jisuodashi.inventory;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.jisuodashi.inventory.DemoFixtures.SLOTS_PER_SHIFT;
import static com.jisuodashi.inventory.DemoFixtures.STORE;
import static com.jisuodashi.inventory.DemoFixtures.SUPPORT_STORE;
import static com.jisuodashi.inventory.DemoFixtures.T1;
import static com.jisuodashi.inventory.DemoFixtures.T1_NAME;
import static com.jisuodashi.inventory.DemoFixtures.T2;
import static com.jisuodashi.inventory.DemoFixtures.T2_NAME;
import static com.jisuodashi.inventory.DemoFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract + HTML report for demo-store generation (FREE vs REST after partial leave).
 * H2 cannot apply V1 DDL; this uses the in-memory store with V3 IDs.
 */
class SlotGenerateReportTest {

    @Test
    void demoStoreReportFreeVsRestAfterLeave() throws IOException {
        InMemorySlotGenerateStore store = DemoFixtures.demoStore();
        store.stores.add(new SlotGenerateStore.StoreRef(SUPPORT_STORE, DemoFixtures.OPEN, DemoFixtures.CLOSE));
        store.exceptions.add(DemoFixtures.leave(11, T1, TODAY, LocalTime.of(14, 0), LocalTime.of(16, 0)));
        store.exceptions.add(DemoFixtures.support(
                12, T2, SUPPORT_STORE, TODAY, LocalTime.of(18, 0), LocalTime.of(22, 0)));

        SlotGenerateResult result = DemoFixtures.service(store).generate(TODAY);

        int expectedTherapist = 3 * 16 * SLOTS_PER_SHIFT;
        int expectedBed = 2 * 16 * SLOTS_PER_SHIFT;
        assertThat(result.firstRun()).isTrue();
        assertThat(result.therapistInserted()).isEqualTo(expectedTherapist);
        assertThat(result.bedInserted()).isEqualTo(expectedBed);
        assertThat(result.restWritten()).isEqualTo(8);
        assertThat(result.freeWritten()).isEqualTo(expectedTherapist - 8);
        assertThat(result.conflicts()).isZero();

        DayGrid t1 = dayGrid(store, T1, TODAY);
        assertThat(t1.rest).isEqualTo(8);
        assertThat(t1.free).isEqualTo(40);
        assertThat(t1.statuses.subList(SlotTimes.toSlotNo(LocalTime.of(14, 0)), SlotTimes.toSlotNo(LocalTime.of(16, 0))))
                .containsOnly(SlotStatus.REST);

        DayGrid t2 = dayGrid(store, T2, TODAY);
        assertThat(t2.storeAt.get(72)).isEqualTo(SUPPORT_STORE);
        assertThat(t2.storeAt.get(40)).isEqualTo(STORE);

        String html = render(result, store, t1, t2);
        Path docs = resolveRepoRoot().resolve("docs/test-cases");
        Files.createDirectories(docs);
        Path report = docs.resolve("pr-3a-slot-report.html");
        Files.writeString(report, html, StandardCharsets.UTF_8);
        Files.createDirectories(resolveTargetDir());
        Files.writeString(resolveTargetDir().resolve("pr-3a-slot-report.html"), html, StandardCharsets.UTF_8);
        assertThat(report).exists();
        assertThat(html).contains("REST").contains("FREE").contains(T1_NAME).contains("8");
    }

    private static DayGrid dayGrid(InMemorySlotGenerateStore store, long therapistId, LocalDate date) {
        DayGrid grid = new DayGrid();
        for (int slot = 0; slot < SlotTimes.SLOTS_PER_DAY; slot++) {
            var row = store.therapistSlot(therapistId, date, slot);
            if (row == null) {
                grid.statuses.add("");
                continue;
            }
            grid.statuses.add(row.status());
            grid.storeAt.put(slot, row.storeId());
            if (SlotStatus.REST.equals(row.status())) {
                grid.rest++;
            } else if (SlotStatus.FREE.equals(row.status())) {
                grid.free++;
            }
        }
        return grid;
    }

    private static String render(
            SlotGenerateResult result,
            InMemorySlotGenerateStore store,
            DayGrid t1,
            DayGrid t2
    ) {
        StringBuilder cells = new StringBuilder();
        for (int hour = 10; hour < 22; hour++) {
            cells.append("<tr><th>").append(String.format("%02d:00", hour)).append("</th>");
            for (int q = 0; q < 4; q++) {
                int slot = hour * 4 + q;
                String status = t1.statuses.get(slot);
                String cls = SlotStatus.REST.equals(status) ? "rest" : "free";
                cells.append("<td class='").append(cls).append("'>")
                        .append(slot).append("<br/>").append(status)
                        .append("</td>");
            }
            cells.append("</tr>\n");
        }

        StringBuilder days = new StringBuilder();
        LocalDate horizon = TODAY.plusDays(15);
        for (LocalDate d = TODAY; !d.isAfter(horizon); d = d.plusDays(1)) {
            int free = 0;
            int rest = 0;
            for (var row : store.therapistSlots.values()) {
                if (row.therapistId() == T1 && row.slotDate().equals(d)) {
                    if (SlotStatus.REST.equals(row.status())) {
                        rest++;
                    } else {
                        free++;
                    }
                }
            }
            days.append("<tr><td>").append(d).append("</td><td>")
                    .append(d.getDayOfWeek()).append("</td><td class='ok'>")
                    .append(free).append("</td><td class='restc'>")
                    .append(rest).append("</td></tr>\n");
        }

        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                  <meta charset="UTF-8"/>
                  <title>PR3a SlotGenerateJob · demo store</title>
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
                    td.restc { color:#8a6a2f; font-weight:700; }
                    td.free { background:#d9efe4; color:#14352c; text-align:center; width:22%; }
                    td.rest { background:#e8e4dc; color:#5c5346; text-align:center; width:22%;
                              text-decoration:line-through; }
                    .legend span { display:inline-block; padding:4px 10px; margin-right:8px;
                                   border-radius:4px; font-size:12px; }
                    .legend .free { background:#d9efe4; }
                    .legend .rest { background:#e8e4dc; }
                    footer { padding:0 40px 32px; color:#6b7c75; font-size:12px; }
                    code { font-family: ui-monospace, Menlo, monospace; font-size:12px; }
                  </style>
                </head>
                <body>
                  <header>
                    <h1>SlotGenerateJob · 演示旗舰店 DEMO01</h1>
                    <p>today=__TODAY__ Asia/Shanghai · window __FROM__ … __TO__ · firstRun=__FIRST__</p>
                    <span class="badge">PASS · therapist +__TINS__ · bed +__BINS__ · REST __REST__ / FREE __FREE__</span>
                  </header>
                  <main>
                    <div class="meta">
                      <span class="pill">1 店 / 3 技师 / 2 床 / 21 周模板</span>
                      <span class="pill">10:00–22:00 = 48 格</span>
                      <span class="pill">林晓 部分日 LEAVE 14:00–16:00 → 8 REST</span>
                      <span class="pill">陈默 SUPPORT store=__SUP__ 18:00–22:00</span>
                      <span class="pill">no BUFFER / occupancy / lockNew</span>
                    </div>
                    <h2>生成计数</h2>
                    <table>
                      <thead><tr><th>资源</th><th>插入</th><th>IGNORE</th><th>冲突 human_task</th></tr></thead>
                      <tbody>
                        <tr><td>therapist_slot</td><td class="ok">__TINS__</td><td>__TIGN__</td><td>__CONF__</td></tr>
                        <tr><td>bed_slot</td><td class="ok">__BINS__</td><td>__BIGN__</td><td>0</td></tr>
                      </tbody>
                    </table>
                    <h2>__T1__ (__T1ID__) · __TODAY__ FREE vs REST</h2>
                    <p class="legend"><span class="free">FREE __T1FREE__</span><span class="rest">REST __T1REST__</span>
                    仅 14:00–16:00 为 REST，全日其余仍 FREE。</p>
                    <table>
                      <thead><tr><th>小时</th><th>:00</th><th>:15</th><th>:30</th><th>:45</th></tr></thead>
                      <tbody>
                __CELLS__      </tbody>
                    </table>
                    <h2>__T1__ 16 日 FREE / REST</h2>
                    <table>
                      <thead><tr><th>日期</th><th>星期</th><th>FREE</th><th>REST</th></tr></thead>
                      <tbody>
                __DAYS__      </tbody>
                    </table>
                    <h2>__T2__ SUPPORT 抽查</h2>
                    <table>
                      <thead><tr><th>slot</th><th>时间</th><th>store_id</th><th>status</th></tr></thead>
                      <tbody>
                        <tr><td>40</td><td>10:00</td><td>__HOME__</td><td>FREE</td></tr>
                        <tr><td>72</td><td>18:00</td><td>__SUP__</td><td>FREE</td></tr>
                        <tr><td>87</td><td>21:45</td><td>__SUP__</td><td>FREE</td></tr>
                      </tbody>
                    </table>
                  </main>
                  <footer>Generated by SlotGenerateReportTest with in-memory fakes (V3 IDs). Flyway/H2 off.</footer>
                </body>
                </html>
                """
                .replace("__TODAY__", TODAY.toString())
                .replace("__FROM__", result.from().toString())
                .replace("__TO__", result.to().toString())
                .replace("__FIRST__", String.valueOf(result.firstRun()))
                .replace("__TINS__", String.valueOf(result.therapistInserted()))
                .replace("__TIGN__", String.valueOf(result.therapistIgnored()))
                .replace("__BINS__", String.valueOf(result.bedInserted()))
                .replace("__BIGN__", String.valueOf(result.bedIgnored()))
                .replace("__REST__", String.valueOf(result.restWritten()))
                .replace("__FREE__", String.valueOf(result.freeWritten()))
                .replace("__CONF__", String.valueOf(result.conflicts()))
                .replace("__T1__", T1_NAME)
                .replace("__T1ID__", String.valueOf(T1))
                .replace("__T1FREE__", String.valueOf(t1.free))
                .replace("__T1REST__", String.valueOf(t1.rest))
                .replace("__T2__", T2_NAME)
                .replace("__HOME__", String.valueOf(STORE))
                .replace("__SUP__", String.valueOf(SUPPORT_STORE))
                .replace("__CELLS__", cells)
                .replace("__DAYS__", days);
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

    private static final class DayGrid {
        final List<String> statuses = new ArrayList<>();
        final Map<Integer, Long> storeAt = new TreeMap<>();
        int free;
        int rest;
    }
}
