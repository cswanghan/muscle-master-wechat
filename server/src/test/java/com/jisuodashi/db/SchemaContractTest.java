package com.jisuodashi.db;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1–V4 source-of-truth contract. H2 cannot execute the MySQL DDL
 * (VARBINARY, DATETIME(3), JSON, comments); Flyway stays off on {@code dev}.
 * Apply against compose MySQL with {@code scripts/verify-schema.sh}.
 */
class SchemaContractTest {

    private static final List<String> REQUIRED_TABLES = List.of(
            "store", "room", "bed", "therapist", "project", "store_project",
            "symptom", "symptom_project", "therapist_project", "therapist_symptom",
            "schedule_template", "schedule_exception",
            "therapist_slot", "bed_slot", "slot_occupancy",
            "customer", "auth_session",
            "booking_order", "order_item", "order_change_log",
            "payment", "refund",
            "service_record", "treatment_note",
            "staff_user", "role", "permission", "staff_role", "role_permission", "data_scope",
            "audit_log", "human_task", "workflow_instance", "workflow_step",
            "delayed_job", "outbox_event", "idempotency_record"
    );

    private static final List<String> ROLE_CODES = List.of(
            "SUPER_ADMIN", "FINANCE", "OPS", "REGION_MANAGER",
            "STORE_MANAGER", "FRONTDESK", "THERAPIST"
    );

    private static final List<String> PERM_CODES = List.of(
            "catalog:store", "catalog:therapist", "catalog:project", "catalog:write",
            "schedule:write", "schedule:approve",
            "order:list", "order:view", "order:refund",
            "frontdesk:order:*",
            "refund:create", "refund:after_start", "refund:approve",
            "inventory:force_release", "staff:self"
    );

    private static String v1;
    private static String v2;
    private static String v3;
    private static String v4;
    private static String v5;
    private static final List<Check> CHECKS = new ArrayList<>();

    @BeforeAll
    static void loadMigrations() throws IOException {
        v1 = readMigration("V1__init.sql");
        v2 = readMigration("V2__rbac_seed.sql");
        v3 = readMigration("V3__demo_store.sql");
        v4 = readMigration("V4__locknew_free_indexes.sql");
        v5 = readMigration("V5__order_resolve_perm.sql");
    }

    @Test
    void schemaContractAndReports() throws IOException {
        tc201DualSlotAndOccupancy();
        tc202WxOpenidNullableUnique();
        tc203NoBalanceFen();
        tc204DemoStore();
        tc205RbacSeed();
        extraInvariants();

        writeReports();

        List<Check> failed = CHECKS.stream().filter(c -> !c.pass).toList();
        assertThat(failed)
                .as("schema contract failures: %s", failed)
                .isEmpty();
    }

    private static void tc201DualSlotAndOccupancy() {
        List<String> ukTherapistSlot = uniqueKeyColumns(v1, "therapist_slot", "uk_therapist_slot");
        List<String> ukBedSlot = uniqueKeyColumns(v1, "bed_slot", "uk_bed_slot");
        List<String> ukOcc = uniqueKeyColumns(v1, "slot_occupancy", "uk_occ");
        check("TC-2-01", "uk_therapist_slot exact (therapist_id, slot_date, slot_no)",
                ukTherapistSlot.equals(List.of("therapist_id", "slot_date", "slot_no")));
        check("TC-2-01", "uk_bed_slot exact (bed_id, slot_date, slot_no)",
                ukBedSlot.equals(List.of("bed_id", "slot_date", "slot_no")));
        check("TC-2-01", "uk_occ exact (resource_type, resource_id, slot_date, slot_no)",
                ukOcc.equals(List.of("resource_type", "resource_id", "slot_date", "slot_no")));
        check("TC-2-01", "uk_occ does not include hold_id",
                !ukOcc.isEmpty() && !ukOcc.contains("hold_id"));
        check("TC-2-01", "therapist_slot.hold_id",
                tableHasColumn(v1, "therapist_slot", "hold_id"));
        check("TC-2-01", "bed_slot.hold_id",
                tableHasColumn(v1, "bed_slot", "hold_id"));
        check("TC-2-01", "slot_occupancy.hold_id NOT NULL",
                tableBody(v1, "slot_occupancy").matches("(?s).*hold_id\\s+BIGINT\\s+NOT NULL.*"));
        check("TC-2-01", "V1 slot tables are not partitioned",
                !v1.toUpperCase().contains("PARTITION BY"));
    }

    private static void tc202WxOpenidNullableUnique() {
        String customer = tableBody(v1, "customer");
        check("TC-2-02", "customer.wx_openid VARCHAR(64) NULL",
                customer.matches("(?s).*wx_openid\\s+VARCHAR\\(64\\)\\s+NULL.*"));
        check("TC-2-02", "customer.wx_openid is not NOT NULL",
                !customer.matches("(?s).*wx_openid\\s+VARCHAR\\(64\\)\\s+NOT NULL.*"));
        check("TC-2-02", "uk_customer_openid (wx_openid)",
                customer.contains("UNIQUE KEY uk_customer_openid (wx_openid)"));
    }

    private static void tc203NoBalanceFen() {
        check("TC-2-03", "no customer.balance_fen",
                !tableHasColumn(v1, "customer", "balance_fen")
                        && !tableHasColumn(v2, "customer", "balance_fen")
                        && !tableHasColumn(v3, "customer", "balance_fen")
                        && !Pattern.compile("(?im)^\\s*balance_fen\\s+").matcher(v1 + "\n" + v2 + "\n" + v3).find());
        check("TC-2-03", "no wallet / ledger tables",
                !v1.matches("(?is).*CREATE TABLE\\s+(wallet|ledger|customer_wallet|account_book).*"));
    }

    private static void tc204DemoStore() {
        check("TC-2-04", "exactly 1 store (DEMO01)",
                countInsertRows(v3, "store") == 1 && v3.contains("'DEMO01'"));
        check("TC-2-04", "exactly 3 therapists T001/T002/T003",
                countInsertRows(v3, "therapist") == 3
                        && v3.contains("'T001'") && v3.contains("'T002'") && v3.contains("'T003'"));
        check("TC-2-04", "exactly 2 beds",
                countInsertRows(v3, "bed") == 2
                        && v3.contains("'1号床'") && v3.contains("'2号床'"));
        check("TC-2-04", "1–2 rooms",
                countInsertRows(v3, "room") >= 1 && countInsertRows(v3, "room") <= 2);
        check("TC-2-04", "weekly schedule templates (21 = 3×7)",
                countInsertRows(v3, "schedule_template") == 21);
        check("TC-2-04", "projects buffer_minutes=15",
                Pattern.compile("'P\\d+',\\s*'[^']+',\\s*\\d+,\\s*15,")
                        .matcher(v3).results().count() == 3);
        check("TC-2-04", "templates effective_from 2026-01-01",
                v3.contains("'2026-01-01'"));
    }

    private static void tc205RbacSeed() {
        for (String code : ROLE_CODES) {
            check("TC-2-05", "role " + code, v2.contains("'" + code + "'"));
        }
        for (String code : PERM_CODES) {
            check("TC-2-05", "permission " + code, v2.contains("'" + code + "'"));
        }
        check("TC-2-05", "7 roles seeded",
                ROLE_CODES.stream().allMatch(c -> v2.contains("'" + c + "'")));
        check("TC-2-05", "V5 seeds order:resolve", v5.contains("'order:resolve'"));
        check("TC-2-05", "order:resolve granted to 3 roles (super/region/store manager)",
                countInsertRows(v5, "role_permission") == 3);
        check("TC-2-05", "order:resolve not granted in V2 (front desk excluded)",
                !v2.contains("'order:resolve'"));
    }

    private static void extraInvariants() {
        for (String table : REQUIRED_TABLES) {
            check("TABLE", "CREATE TABLE " + table, hasCreateTable(v1, table));
        }
        check("D10", "booking_order money columns are BIGINT fen",
                tableHasColumn(v1, "booking_order", "origin_price_fen")
                        && tableHasColumn(v1, "booking_order", "payable_fen")
                        && tableHasColumn(v1, "project", "price_fen")
                        && !v1.matches("(?is).*\\bDECIMAL\\s*\\(\\s*\\d+\\s*,\\s*2\\s*\\).*"));
        check("D10", "DATETIME(3) used", v1.contains("DATETIME(3)"));
        check("D12", "booking_order.therapist_home_store_id",
                tableHasColumn(v1, "booking_order", "therapist_home_store_id"));
        check("D12", "booking_order.store_id + hold_id + request_id",
                tableHasColumn(v1, "booking_order", "store_id")
                        && tableHasColumn(v1, "booking_order", "hold_id")
                        && tableHasColumn(v1, "booking_order", "request_id"));
        check("D21", "idempotency_record uk_idem (scope, request_id)",
                tableBody(v1, "idempotency_record").contains("UNIQUE KEY uk_idem (scope, request_id)"));
        check("D16", "delayed_job has lease columns",
                tableHasColumn(v1, "delayed_job", "lease_until")
                        && tableHasColumn(v1, "delayed_job", "locked_by")
                        && tableHasColumn(v1, "delayed_job", "locked_at"));
        check("D16", "outbox_event has no lease columns",
                !tableHasColumn(v1, "outbox_event", "lease_until")
                        && !tableHasColumn(v1, "outbox_event", "locked_by"));
        check("SWAP", "service_record has no UNIQUE on order_id",
                !tableBody(v1, "service_record").matches("(?is).*UNIQUE KEY\\s+\\w+\\s*\\(\\s*order_id\\s*\\).*"));
        check("ENGINE", "InnoDB utf8mb4 on store",
                v1.contains("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"));
        check("PR3b", "idx_ts_free (therapist_id, slot_date, status, slot_no)",
                v4.contains("idx_ts_free (therapist_id, slot_date, status, slot_no)"));
        check("PR3b", "idx_bs_free (bed_id, slot_date, status, slot_no)",
                v4.contains("idx_bs_free (bed_id, slot_date, status, slot_no)"));
    }

    private static void writeReports() throws IOException {
        Path repoRoot = resolveRepoRoot();
        Path targetDir = resolveTargetDir();
        Files.createDirectories(targetDir);
        Path docsDir = repoRoot.resolve("docs/test-cases");
        Files.createDirectories(docsDir);

        String preview = renderSchemaPreview();
        Files.writeString(docsDir.resolve("schema-preview.html"), preview, StandardCharsets.UTF_8);
        Files.writeString(targetDir.resolve("schema-preview.html"), preview, StandardCharsets.UTF_8);

        String report = renderContractReport();
        Files.writeString(targetDir.resolve("schema-contract-report.html"), report, StandardCharsets.UTF_8);
        Files.writeString(docsDir.resolve("schema-contract-report.html"), report, StandardCharsets.UTF_8);
    }

    private static String renderSchemaPreview() {
        Map<String, TableInfo> tables = parseTables(v1);
        StringBuilder rows = new StringBuilder();
        int i = 0;
        for (TableInfo t : tables.values()) {
            i++;
            rows.append("<tr>")
                    .append("<td class='n'>").append(i).append("</td>")
                    .append("<td><code>").append(esc(t.name)).append("</code></td>")
                    .append("<td>").append(esc(t.comment)).append("</td>")
                    .append("<td>").append(t.columns.size()).append("</td>")
                    .append("<td>").append(esc(String.join(", ", t.uniques))).append("</td>")
                    .append("<td>").append(esc(t.primaryKey)).append("</td>")
                    .append("</tr>\n");
        }
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                  <meta charset="UTF-8"/>
                  <title>P0 Schema Preview · V1</title>
                  <style>
                    :root { --ink:#14352c; --brand:#1E5C4A; --bg:#f4f7f5; --line:#d5e3dc; }
                    body { font-family: ui-sans-serif, "PingFang SC", sans-serif; margin:0;
                           background:var(--bg); color:var(--ink); }
                    header { background:var(--brand); color:#fff; padding:28px 40px; }
                    header h1 { margin:0 0 6px; font-size:22px; }
                    header p { margin:0; opacity:.85; }
                    main { padding:28px 40px 48px; }
                    .meta { display:flex; gap:12px; flex-wrap:wrap; margin-bottom:20px; }
                    .pill { background:#fff; border:1px solid var(--line); border-radius:999px;
                            padding:6px 14px; font-size:13px; }
                    table { width:100%; border-collapse:collapse; background:#fff;
                            box-shadow:0 1px 3px rgba(20,53,44,.06); }
                    th, td { text-align:left; padding:10px 12px; border-bottom:1px solid var(--line);
                             font-size:13px; vertical-align:top; }
                    th { background:#e8f1ed; color:var(--brand); font-weight:600; }
                    tr:hover td { background:#f7fbf9; }
                    .n { color:#7a8f87; width:36px; }
                    code { font-family: ui-monospace, Menlo, monospace; font-size:12px; }
                    footer { padding:0 40px 32px; color:#6b7c75; font-size:12px; }
                  </style>
                </head>
                <body>
                  <header>
                    <h1>肌松大师 · P0 Schema Preview</h1>
                    <p>Parsed from Flyway <code>V1__init.sql</code> · dual-resource slots · no partitions</p>
                  </header>
                  <main>
                    <div class="meta">
                      <span class="pill">Tables: __TABLE_COUNT__</span>
                      <span class="pill">uk_therapist_slot + uk_bed_slot + uk_occ</span>
                      <span class="pill">customer.wx_openid NULL UNIQUE</span>
                      <span class="pill">no balance_fen</span>
                      <span class="pill">InnoDB utf8mb4 · DATETIME(3) · BIGINT fen</span>
                    </div>
                    <table>
                      <thead>
                        <tr><th>#</th><th>Table</th><th>Comment</th><th>Cols</th><th>Unique keys</th><th>PK</th></tr>
                      </thead>
                      <tbody>
                __ROWS__      </tbody>
                    </table>
                  </main>
                  <footer>Generated by SchemaContractTest from V1 source of truth. Flyway off on H2 dev profile.</footer>
                </body>
                </html>
                """.replace("__TABLE_COUNT__", String.valueOf(tables.size()))
                .replace("__ROWS__", rows.toString());
    }

    private static String renderContractReport() {
        long passed = CHECKS.stream().filter(c -> c.pass).count();
        StringBuilder rows = new StringBuilder();
        for (Check c : CHECKS) {
            rows.append("<tr class='").append(c.pass ? "ok" : "bad").append("'>")
                    .append("<td>").append(esc(c.id)).append("</td>")
                    .append("<td>").append(esc(c.name)).append("</td>")
                    .append("<td>").append(c.pass ? "PASS" : "FAIL").append("</td>")
                    .append("</tr>\n");
        }
        String status = passed == CHECKS.size() ? "ALL PASS" : "FAILED";
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                  <meta charset="UTF-8"/>
                  <title>SchemaContractTest · __STATUS__</title>
                  <style>
                    body { font-family: ui-sans-serif, "PingFang SC", sans-serif; margin:0;
                           background:#0f1f1a; color:#e8f1ed; }
                    header { padding:28px 40px; background:#1E5C4A; }
                    header h1 { margin:0 0 8px; font-size:22px; }
                    .badge { display:inline-block; background:#2fbf71; color:#062014;
                             font-weight:700; padding:4px 12px; border-radius:6px; letter-spacing:.04em; }
                    .badge.fail { background:#e85d4c; color:#fff; }
                    main { padding:24px 40px 48px; }
                    table { width:100%; border-collapse:collapse; }
                    th, td { text-align:left; padding:8px 10px; border-bottom:1px solid #2a463c; font-size:13px; }
                    tr.ok td:last-child { color:#3ddc84; font-weight:700; }
                    tr.bad td:last-child { color:#ff8a7a; font-weight:700; }
                    code { font-family: ui-monospace, Menlo, monospace; }
                  </style>
                </head>
                <body>
                  <header>
                    <h1>SchemaContractTest</h1>
                    <span class="badge __BADGE_CLASS__">__STATUS__ · __PASSED__ / __TOTAL__</span>
                    <p style="margin:10px 0 0;opacity:.85">V1 dual-slot unique keys · occupancy · RBAC · demo store</p>
                  </header>
                  <main>
                    <table>
                      <thead><tr><th>Case</th><th>Check</th><th>Result</th></tr></thead>
                      <tbody>
                __ROWS__      </tbody>
                    </table>
                  </main>
                </body>
                </html>
                """.replace("__STATUS__", status)
                .replace("__BADGE_CLASS__", passed == CHECKS.size() ? "" : "fail")
                .replace("__PASSED__", String.valueOf(passed))
                .replace("__TOTAL__", String.valueOf(CHECKS.size()))
                .replace("__ROWS__", rows.toString());
    }

    private record Check(String id, String name, boolean pass) {
        @Override
        public String toString() {
            return id + " " + name + "=" + pass;
        }
    }

    private record TableInfo(String name, String comment, List<String> columns, List<String> uniques, String primaryKey) {
    }

    private static void check(String id, String name, boolean pass) {
        CHECKS.add(new Check(id, name, pass));
    }

    private static String readMigration(String file) throws IOException {
        ClassPathResource resource = new ClassPathResource("db/migration/" + file);
        assertThat(resource.exists()).as("missing %s", file).isTrue();
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    private static boolean hasCreateTable(String sql, String table) {
        return Pattern.compile("CREATE TABLE\\s+" + table + "\\s*\\(", Pattern.CASE_INSENSITIVE)
                .matcher(sql).find();
    }

    private static String tableBody(String sql, String table) {
        Matcher m = Pattern.compile(
                "CREATE TABLE\\s+" + table + "\\s*\\((.*?)\\)\\s*ENGINE",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(sql);
        return m.find() ? m.group(1) : "";
    }

    private static boolean tableHasColumn(String sql, String table, String column) {
        return Pattern.compile("(?m)^\\s*" + column + "\\s+", Pattern.CASE_INSENSITIVE)
                .matcher(tableBody(sql, table)).find();
    }

    /** Column list of a named UNIQUE KEY; empty if the key is missing. Exact order, trimmed. */
    private static List<String> uniqueKeyColumns(String sql, String table, String keyName) {
        Matcher uk = Pattern.compile(
                "UNIQUE KEY\\s+" + Pattern.quote(keyName) + "\\s*\\(([^)]+)\\)",
                Pattern.CASE_INSENSITIVE).matcher(tableBody(sql, table));
        if (!uk.find()) {
            return List.of();
        }
        List<String> cols = new ArrayList<>();
        for (String part : uk.group(1).split(",")) {
            String col = part.trim();
            if (!col.isEmpty()) {
                cols.add(col);
            }
        }
        return List.copyOf(cols);
    }

    private static int countInsertRows(String sql, String table) {
        Matcher m = Pattern.compile(
                "INSERT INTO\\s+" + table + "\\s*(?:\\([^;]*?\\)\\s*)?VALUES\\s*(.*?);",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(sql);
        int rows = 0;
        while (m.find()) {
            rows += countTopLevelTuples(m.group(1));
        }
        return rows;
    }

    private static int countTopLevelTuples(String values) {
        int depth = 0;
        int count = 0;
        for (int i = 0; i < values.length(); i++) {
            char c = values.charAt(i);
            if (c == '(') {
                if (depth == 0) {
                    count++;
                }
                depth++;
            } else if (c == ')') {
                depth--;
            }
        }
        return count;
    }

    private static Map<String, TableInfo> parseTables(String sql) {
        Map<String, TableInfo> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile(
                "CREATE TABLE\\s+(\\w+)\\s*\\((.*?)\\)\\s*ENGINE=InnoDB[^;]*COMMENT='([^']*)'",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(sql);
        while (m.find()) {
            String name = m.group(1);
            String body = m.group(2);
            String comment = m.group(3);
            List<String> cols = new ArrayList<>();
            Matcher col = Pattern.compile("(?m)^\\s*([a-z_][a-z0-9_]*)\\s+[A-Z]", Pattern.CASE_INSENSITIVE)
                    .matcher(body);
            while (col.find()) {
                String ident = col.group(1);
                if (!ident.equalsIgnoreCase("UNIQUE")
                        && !ident.equalsIgnoreCase("PRIMARY")
                        && !ident.equalsIgnoreCase("KEY")
                        && !ident.equalsIgnoreCase("CONSTRAINT")) {
                    cols.add(ident);
                }
            }
            List<String> uniques = new ArrayList<>();
            Matcher uk = Pattern.compile("UNIQUE KEY\\s+(\\w+)\\s*\\(([^)]+)\\)", Pattern.CASE_INSENSITIVE)
                    .matcher(body);
            while (uk.find()) {
                uniques.add(uk.group(1) + " (" + uk.group(2).replaceAll("\\s+", " ").trim() + ")");
            }
            String pk = "";
            Matcher inlinePk = Pattern.compile(
                    "(?m)^\\s*(\\w+)\\s+[^,\\n]*PRIMARY KEY", Pattern.CASE_INSENSITIVE).matcher(body);
            if (inlinePk.find()) {
                pk = inlinePk.group(1);
            }
            Matcher tablePk = Pattern.compile("PRIMARY KEY\\s*\\(([^)]+)\\)", Pattern.CASE_INSENSITIVE)
                    .matcher(body);
            if (tablePk.find()) {
                pk = tablePk.group(1).replaceAll("\\s+", " ").trim();
            }
            out.put(name, new TableInfo(name, comment, cols, uniques, pk));
        }
        return out;
    }

    private static Path resolveRepoRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (Files.isRegularFile(cwd.resolve("server/pom.xml"))) {
            return cwd;
        }
        if (Files.isRegularFile(cwd.resolve("pom.xml")) && cwd.getFileName().toString().equals("server")) {
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

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
