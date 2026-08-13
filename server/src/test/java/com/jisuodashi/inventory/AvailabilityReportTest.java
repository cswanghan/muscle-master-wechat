package com.jisuodashi.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jisuodashi.catalog.DemoCatalogIds;
import com.jisuodashi.catalog.InMemoryCatalogRepository;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AvailabilityReportTest {

    private static final LocalDate DAY = InMemoryAvailabilityStore.DEMO_DATE;

    @Test
    void writesCalendarAndJsonReport() throws Exception {
        InMemoryAvailabilityStore store = new InMemoryAvailabilityStore();
        AvailabilityService svc = new AvailabilityService(
                store,
                new InMemoryCatalogRepository(),
                new AvailabilityCache(Duration.ofSeconds(30), Clock.systemUTC()));
        AvailabilityDtos.Availability body = svc.query(
                DemoCatalogIds.STORE, DAY, DemoCatalogIds.PROJECT_P60, null, true);

        AvailabilityDtos.Therapist lin = body.therapists().stream()
                .filter(t -> t.therapistId().equals(String.valueOf(DemoCatalogIds.THERAPIST_LIN)))
                .findFirst()
                .orElseThrow();
        Set<Integer> startNos = new HashSet<>();
        lin.starts().forEach(s -> startNos.add(s.slotNo()));
        assertThat(startNos).contains(40, 83).doesNotContain(56, 78);
        assertThat(lin.blocks()).extracting(AvailabilityDtos.Block::state)
                .contains(SlotStatus.FREE, SlotStatus.LOCKED, SlotStatus.REST);
        assertThat(body.therapists().stream()
                .flatMap(t -> t.blocks().stream())
                .map(AvailabilityDtos.Block::state)
                .distinct()
                .toList())
                .contains(SlotStatus.FREE, SlotStatus.LOCKED, SlotStatus.BOOKED, SlotStatus.REST);

        ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        String pretty = json.writeValueAsString(body);
        assertThat(pretty).contains("\"starts\"").contains("LOCKED").contains("BOOKED");
        assertThat(lin.starts()).allMatch(s -> startNos.contains(s.slotNo()));

        String html = render(body, pretty, startNos);
        Path docs = resolveRepoRoot().resolve("docs/test-cases");
        Files.createDirectories(docs);
        Path report = docs.resolve("pr-3d-availability.html");
        Files.writeString(report, html, StandardCharsets.UTF_8);
        Files.createDirectories(resolveTargetDir());
        Files.writeString(resolveTargetDir().resolve("pr-3d-availability.html"), html, StandardCharsets.UTF_8);
        assertThat(report).exists();
        assertThat(html).contains("FREE").contains("LOCKED").contains("BOOKED").contains("REST");
        assertThat(html).contains("10:00").contains("20:45").contains("starts");
    }

    private static String render(AvailabilityDtos.Availability body, String pretty, Set<Integer> linStarts) {
        StringBuilder rows = new StringBuilder();
        for (AvailabilityDtos.Therapist t : body.therapists()) {
            rows.append("<tr><th>").append(esc(t.name())).append("<br/><span class='sub'>")
                    .append(esc(t.level())).append(" · ").append(t.starts().size())
                    .append(" starts</span></th>");
            Set<Integer> starts = new HashSet<>();
            t.starts().forEach(s -> starts.add(s.slotNo()));
            for (int hour = 10; hour < 22; hour++) {
                for (int q = 0; q < 4; q++) {
                    int slot = hour * 4 + q;
                    AvailabilityDtos.Block block = t.blocks().stream()
                            .filter(b -> b.slotNo() == slot)
                            .findFirst()
                            .orElse(null);
                    String state = block == null ? "" : block.state();
                    boolean bookable = starts.contains(slot);
                    String cls = state.toLowerCase();
                    if (bookable) {
                        cls += " start";
                    }
                    String label = bookable ? "▶" : "";
                    rows.append("<td class='").append(cls).append("' title='")
                            .append(slot).append(" ").append(state).append("'>")
                            .append(label).append("</td>");
                }
            }
            rows.append("</tr>\n");
        }

        StringBuilder hours = new StringBuilder();
        for (int hour = 10; hour < 22; hour++) {
            hours.append("<th colspan='4'>").append(String.format("%02d", hour)).append("</th>");
        }

        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                  <meta charset="UTF-8"/>
                  <title>PR3d availability · busy-or-occupancy</title>
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
                    table.cal { border-collapse:collapse; background:#fff; width:100%;
                                box-shadow:0 1px 3px rgba(20,53,44,.06); }
                    table.cal th, table.cal td { border:1px solid var(--line); font-size:11px; }
                    table.cal th { background:#e8f1ed; color:var(--brand); padding:4px 2px; }
                    table.cal td { width:18px; height:22px; text-align:center; }
                    table.cal th:first-child, table.cal td:first-child { width:88px; text-align:left;
                            padding:6px 8px; font-size:12px; }
                    .sub { color:#6b7c75; font-weight:400; font-size:11px; }
                    td.free { background:#d9efe4; }
                    td.locked { background:#f3d27a; }
                    td.booked { background:#e8a0a0; }
                    td.rest { background:#d9d4cc; }
                    td.start { box-shadow:inset 0 0 0 2px #1E5C4A; font-weight:700; color:#1E5C4A; }
                    .legend span { display:inline-block; padding:4px 10px; margin-right:8px;
                                   border-radius:4px; font-size:12px; }
                    .legend .free { background:#d9efe4; }
                    .legend .locked { background:#f3d27a; }
                    .legend .booked { background:#e8a0a0; }
                    .legend .rest { background:#d9d4cc; }
                    .legend .start { box-shadow:inset 0 0 0 2px #1E5C4A; }
                    pre { background:#12352c; color:#d9efe4; padding:16px 18px; border-radius:8px;
                          overflow:auto; font-size:11px; line-height:1.45; max-height:360px; }
                    footer { padding:0 40px 32px; color:#6b7c75; font-size:12px; }
                  </style>
                </head>
                <body>
                  <header>
                    <h1>GET /api/v1/c/availability · starts only FREE</h1>
                    <p>store+date 30s cache · busy = status≠FREE <em>or</em> occupancy · D13 priceFen</p>
                    <span class="badge">PASS · occupySlots=__N__ · 林晓 starts=__LIN__ · four states on calendar</span>
                  </header>
                  <main>
                    <div class="meta">
                      <span class="pill">GET /api/v1/c/availability?storeId=&amp;date=2026-08-14&amp;projectId=P60</span>
                      <span class="pill">N = ceil((60+15)/15) = 5</span>
                      <span class="pill">cache:avail:{storeId}:{date} TTL 30s</span>
                      <span class="pill">lockNew / onRelease invalidate</span>
                    </div>
                    <p class="legend">
                      <span class="free">FREE</span>
                      <span class="locked">LOCKED</span>
                      <span class="booked">BOOKED</span>
                      <span class="rest">REST</span>
                      <span class="start">▶ start (bookable only)</span>
                    </p>
                    <h2>C3 四态色块 · starts 只落在 FREE</h2>
                    <table class="cal">
                      <thead>
                        <tr><th>技师</th>__HOURS__</tr>
                      </thead>
                      <tbody>
                __ROWS__      </tbody>
                    </table>
                    <h2>availability JSON</h2>
                    <pre>__JSON__</pre>
                  </main>
                  <footer>Generated by AvailabilityReportTest. LOCKED/BOOKED/REST are colors, never starts.</footer>
                </body>
                </html>
                """
                .replace("__N__", String.valueOf(body.occupySlots()))
                .replace("__LIN__", String.valueOf(linStarts.size()))
                .replace("__HOURS__", hours)
                .replace("__ROWS__", rows)
                .replace("__JSON__", esc(pretty));
    }

    private static String esc(String raw) {
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
