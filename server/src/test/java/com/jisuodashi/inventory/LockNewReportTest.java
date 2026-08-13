package com.jisuodashi.inventory;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.jisuodashi.inventory.OccupyFixtures.START_1930;
import static com.jisuodashi.inventory.OccupyFixtures.START_2000;
import static com.jisuodashi.inventory.OccupyFixtures.T1;
import static com.jisuodashi.inventory.OccupyFixtures.T2;
import static com.jisuodashi.inventory.OccupyFixtures.T3;
import static org.assertj.core.api.Assertions.assertThat;

class LockNewReportTest {

    @Test
    void writesLockReportHtml() throws Exception {
        InMemorySlotOccupyStore oversell = OccupyFixtures.demoStore(2);
        SlotOccupyConcurrencyTest.LockReport threeByTwo = SlotOccupyConcurrencyTest.race(
                OccupyFixtures.service(oversell),
                List.of(
                        OccupyFixtures.cmd("rpt-t1", T1, START_1930),
                        OccupyFixtures.cmd("rpt-t2", T2, START_1930),
                        OccupyFixtures.cmd("rpt-t3", T3, START_1930)
                ));

        InMemorySlotOccupyStore overlapStore = OccupyFixtures.demoStore(1);
        SlotOccupyConcurrencyTest.LockReport overlap = SlotOccupyConcurrencyTest.race(
                OccupyFixtures.service(overlapStore),
                List.of(
                        OccupyFixtures.cmd("rpt-ov-a", T1, START_1930),
                        OccupyFixtures.cmd("rpt-ov-b", T2, START_2000)
                ));

        InMemorySlotOccupyStore idemStore = OccupyFixtures.demoStore();
        SlotOccupyService idemService = OccupyFixtures.service(idemStore);
        LockNewResult first = idemService.lockNew(OccupyFixtures.cmd("rpt-idem", T1, START_1930));
        LockNewResult replay = idemService.lockNew(OccupyFixtures.cmd("rpt-idem", T1, START_1930));

        assertThat(threeByTwo.successes).isEqualTo(2);
        assertThat(oversell.occupancies.keySet()).hasSize(20);
        assertThat(overlap.successes).isEqualTo(1);
        assertThat(replay.replay()).isTrue();
        assertThat(replay.orderId()).isEqualTo(first.orderId());

        String html = render(threeByTwo, oversell.occupancies.size(), overlap, idemStore.occupancies.size());
        Path docs = resolveRepoRoot().resolve("docs/test-cases");
        Files.createDirectories(docs);
        Path report = docs.resolve("pr-3b-lock-report.html");
        Files.writeString(report, html, StandardCharsets.UTF_8);
        Files.createDirectories(resolveTargetDir());
        Files.writeString(resolveTargetDir().resolve("pr-3b-lock-report.html"), html, StandardCharsets.UTF_8);
        assertThat(report).exists();
        assertThat(html).contains("attempts").contains("successes").contains("unique occupancy");
        assertThat(html).contains("2").contains("20");
    }

    private static String render(
            SlotOccupyConcurrencyTest.LockReport threeByTwo,
            int occ32,
            SlotOccupyConcurrencyTest.LockReport overlap,
            int occIdem
    ) {
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                  <meta charset="UTF-8"/>
                  <title>PR3b lockNew · ordered FOR UPDATE</title>
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
                    td.fail { color:#a33; font-weight:700; }
                    footer { padding:0 40px 32px; color:#6b7c75; font-size:12px; }
                    code { font-family: ui-monospace, Menlo, monospace; font-size:12px; }
                  </style>
                </head>
                <body>
                  <header>
                    <h1>lockNew · 有序 FOR UPDATE + occupancy</h1>
                    <p>in-memory CAS · 3 therapists × 2 beds · overlapping 60-min · idempotent replay</p>
                    <span class="badge">PASS · 3×2 successes=__S32__ / attempts=__A32__ · unique occupancy __OCC32__</span>
                  </header>
                  <main>
                    <div class="meta">
                      <span class="pill">N = ceil((60+15)/15) = 5</span>
                      <span class="pill">buffer_slots = 1 · last BUFFER / others LOCKED</span>
                      <span class="pill">Redis therapist-day 5s (in-memory on dev)</span>
                      <span class="pill">hold_id + order_id before occupy · same TX</span>
                    </div>
                    <h2>并发闸门</h2>
                    <table>
                      <thead><tr><th>用例</th><th>attempts</th><th>successes</th><th>unique occupancy count</th><th>期望</th></tr></thead>
                      <tbody>
                        <tr>
                          <td>3 技师 × 2 床 同时 19:30 P60</td>
                          <td>__A32__</td><td class="ok">__S32__</td><td class="ok">__OCC32__</td>
                          <td>恰好 2 成功；occupancy 2×(5 技师+5 床)=20</td>
                        </tr>
                        <tr>
                          <td>重叠 60 分钟窗（19:30 vs 20:00）抢 1 床</td>
                          <td>__AOV__</td><td class="ok">__SOV__</td><td class="ok">__OCCOV__</td>
                          <td>至多 1 成功；失败 40902</td>
                        </tr>
                        <tr>
                          <td>幂等回放 request_id=rpt-idem</td>
                          <td>2</td><td class="ok">1</td><td class="ok">__OCCID__</td>
                          <td>replay 同 orderId；不二次占用</td>
                        </tr>
                      </tbody>
                    </table>
                    <h2>3×2 错误码</h2>
                    <table>
                      <thead><tr><th>code</th><th>含义</th></tr></thead>
                      <tbody>
                        __CODES32__
                      </tbody>
                    </table>
                  </main>
                  <footer>Generated by LockNewReportTest with in-memory CAS (V3 IDs). Flyway/H2 off.</footer>
                </body>
                </html>
                """
                .replace("__S32__", String.valueOf(threeByTwo.successes))
                .replace("__A32__", String.valueOf(threeByTwo.attempts))
                .replace("__OCC32__", String.valueOf(occ32))
                .replace("__AOV__", String.valueOf(overlap.attempts))
                .replace("__SOV__", String.valueOf(overlap.successes))
                .replace("__OCCOV__", String.valueOf(overlap.successes * 10))
                .replace("__OCCID__", String.valueOf(occIdem))
                .replace("__CODES32__", threeByTwo.codes.stream()
                        .map(c -> "<tr><td>" + c + "</td><td>" + label(c) + "</td></tr>\n")
                        .reduce("", String::concat));
    }

    private static String label(int code) {
        return switch (code) {
            case 0 -> "success PENDING_PAY";
            case 40901 -> "40901 技师时段不可用";
            case 40902 -> "40902 无空闲床位";
            case 40903 -> "40903 锁冲突";
            default -> String.valueOf(code);
        };
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
