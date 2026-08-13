package com.jisuodashi.frontdesk;

import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.auth.CollisionTaskWriter;
import com.jisuodashi.auth.CustomerMergeService;
import com.jisuodashi.auth.DemoStaffIds;
import com.jisuodashi.auth.InMemoryAuthSessionRepository;
import com.jisuodashi.auth.InMemoryCustomerRepository;
import com.jisuodashi.auth.InMemoryRelatedRecordsRepository;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.TokenType;
import com.jisuodashi.catalog.DemoCatalogIds;
import com.jisuodashi.catalog.InMemoryCatalogRepository;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.PhoneCrypto;
import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.inventory.OccupyFixtures;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.inventory.SlotStatus;
import com.jisuodashi.order.FireContext;
import com.jisuodashi.order.OrderEvent;
import com.jisuodashi.order.OrderStateMachine;
import com.jisuodashi.payment.InMemoryPaymentStore;
import com.jisuodashi.payment.MockWeChatPayClient;
import com.jisuodashi.payment.Payment;
import com.jisuodashi.payment.PaymentService;
import com.jisuodashi.rbac.DataScopeType;
import com.jisuodashi.rbac.StoreScope;
import com.jisuodashi.rbac.StoreScopeContext;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FrontDeskAddOnReportTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 14);

    @Test
    void writeHtmlReport() throws Exception {
        List<Row> rows = new ArrayList<>();
        Fixture cash = fixture();
        FrontDeskDtos.WalkInResponse walked = cash.desk.walkIn(walk("rpt-ao-cash", "18600007777", 64));
        cash.machine.fire(Long.parseLong(walked.orderId()), OrderEvent.START_SERVICE, FireContext.system());
        FrontDeskDtos.AddOnResponse cashAdd = cash.desk.addOn(
                walked.orderId(), addOn("rpt-ao-c", 30, "CASH"));
        rows.add(row("CASH", "IN_SERVICE 现金 extendOwn 后 fire(ADD_ON)，原 BUFFER 变 BOOKED",
                "IN_SERVICE".equals(cashAdd.status())
                        && cashAdd.amountFen() == 9900
                        && cashAdd.endSlotNo() == 71
                        && SlotStatus.BOOKED.equals(cash.store.therapistSlot(
                        DemoCatalogIds.THERAPIST_LIN, TODAY, 68).status)
                        && SlotStatus.BUFFER.equals(cash.store.therapistSlot(
                        DemoCatalogIds.THERAPIST_LIN, TODAY, 70).status),
                "status=" + cashAdd.status() + " amt=" + cashAdd.amountFen()
                        + " end=" + cashAdd.endSlotNo()));

        Fixture wx = fixture();
        FrontDeskDtos.WalkInResponse wxWalk = wx.desk.walkIn(walk("rpt-ao-wx", "18600008888", 48));
        wx.machine.fire(Long.parseLong(wxWalk.orderId()), OrderEvent.START_SERVICE, FireContext.system());
        FrontDeskDtos.AddOnResponse wxAdd = wx.desk.addOn(
                wxWalk.orderId(), addOn("rpt-ao-w", 30, "WECHAT"));
        int endHeld = wx.store.findOrderById(Long.parseLong(wxWalk.orderId())).endSlotNo();
        wx.pay.onWechatNotify(
                "{\"out_trade_no\":\"" + wxAdd.paymentNo() + "\",\"amount_fen\":9900}", Map.of());
        Payment polled = wx.payments.findByPaymentNo(wxAdd.paymentNo());
        rows.add(row("WECHAT", "extendOwn LOCKED + Native 轮询；notify 后 fire(ADD_ON) 不 fire(PAY_SUCCESS)",
                wxAdd.codeUrl() != null && wxAdd.codeUrl().contains("LIVE_")
                        && endHeld == 53
                        && polled != null && polled.success()
                        && "IN_SERVICE".equals(wx.store.findOrderById(Long.parseLong(wxWalk.orderId())).status())
                        && wx.store.findOrderById(Long.parseLong(wxWalk.orderId())).endSlotNo() == 55
                        && wx.store.findOrderById(Long.parseLong(wxWalk.orderId())).addOnHoldId() == null,
                "qr=" + wxAdd.codeUrl() + " poll=" + (polled == null ? "null" : polled.status())
                        + " end=" + wx.store.findOrderById(Long.parseLong(wxWalk.orderId())).endSlotNo()));

        Fixture conflict = fixture();
        FrontDeskDtos.WalkInResponse busyWalk = conflict.desk.walkIn(walk("rpt-ao-busy", "18600006666", 56));
        conflict.machine.fire(Long.parseLong(busyWalk.orderId()), OrderEvent.START_SERVICE, FireContext.system());
        conflict.store.therapistSlot(DemoCatalogIds.THERAPIST_LIN, TODAY, 61).status = SlotStatus.BOOKED;
        int occBefore = conflict.store.occupancyCount();
        int code = 0;
        try {
            conflict.desk.addOn(busyWalk.orderId(), addOn("rpt-ao-b", 30, "CASH"));
        } catch (ApiException ex) {
            code = ex.getCode();
        }
        rows.add(row("40907", "后续格占用 → 40907，不部分占格、不建 workflow",
                code == ErrorCodes.ADD_ON_CONFLICT
                        && conflict.store.occupancyCount() == occBefore
                        && conflict.store.findOrderById(Long.parseLong(busyWalk.orderId())).addOnHoldId() == null,
                "code=" + code + " occ=" + conflict.store.occupancyCount()));

        Fixture timeout = fixture();
        FrontDeskDtos.WalkInResponse toWalk = timeout.desk.walkIn(walk("rpt-ao-to", "18600005555", 40));
        timeout.machine.fire(Long.parseLong(toWalk.orderId()), OrderEvent.START_SERVICE, FireContext.system());
        timeout.desk.addOn(toWalk.orderId(), addOn("rpt-ao-t", 15, "WECHAT"));
        timeout.machine.fire(Long.parseLong(toWalk.orderId()), OrderEvent.ADD_ON_PAY_TIMEOUT, FireContext.job());
        rows.add(row("TIMEOUT", "ADD_ON_PAY_TIMEOUT → ReleaseAddOnHold 恢复 BUFFER，订单仍 IN_SERVICE",
                timeout.store.findOrderById(Long.parseLong(toWalk.orderId())).addOnHoldId() == null
                        && "IN_SERVICE".equals(timeout.store.findOrderById(Long.parseLong(toWalk.orderId())).status())
                        && SlotStatus.BUFFER.equals(timeout.store.therapistSlot(
                        DemoCatalogIds.THERAPIST_LIN, TODAY, 44).status)
                        && SlotStatus.FREE.equals(timeout.store.therapistSlot(
                        DemoCatalogIds.THERAPIST_LIN, TODAY, 45).status),
                "hold=" + timeout.store.findOrderById(Long.parseLong(toWalk.orderId())).addOnHoldId()));

        String html = render(rows);
        Path docs = resolveRepoRoot().resolve("docs/test-cases");
        Files.createDirectories(docs);
        Path report = docs.resolve("pr-13-addon.html");
        Files.writeString(report, html, StandardCharsets.UTF_8);
        Files.createDirectories(resolveTargetDir());
        Files.writeString(resolveTargetDir().resolve("pr-13-addon.html"), html, StandardCharsets.UTF_8);
        screenshot(report);

        List<Row> failed = rows.stream().filter(r -> !r.pass).toList();
        assertThat(failed).as("pr-13 report failures: %s", failed).isEmpty();
        assertThat(html).contains("ADD_ON").contains("40907").contains("extendOwn");
        assertThat(report).exists();
        StoreScopeContext.clear();
        AuthContext.clear();
    }

    private static Fixture fixture() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService occupy = OccupyFixtures.service(store);
        AppClock clock = new AppClock(Clock.fixed(
                TODAY.atTime(LocalTime.of(19, 0)).atZone(AppClock.SHANGHAI).toInstant(),
                AppClock.SHANGHAI));
        AppProperties props = new AppProperties();
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(props);
        OrderStateMachine machine = new OrderStateMachine(store, occupy, clock);
        InMemoryPaymentStore payments = new InMemoryPaymentStore();
        PaymentService pay = new PaymentService(
                payments, store, machine, new MockWeChatPayClient(clock), ids, clock, occupy);
        InMemoryCustomerRepository customers = new InMemoryCustomerRepository();
        PhoneCrypto crypto = new PhoneCrypto(props);
        CustomerMergeService merge = new CustomerMergeService(
                customers,
                new InMemoryRelatedRecordsRepository(ids, clock.clock()),
                new InMemoryAuthSessionRepository(),
                new CollisionTaskWriter(new InMemoryRelatedRecordsRepository(ids, clock.clock())),
                ids,
                clock.clock());
        FrontDeskService desk = new FrontDeskService(
                occupy, store, machine, pay, merge, customers, crypto, clock, new InMemoryCatalogRepository());
        StoreScopeContext.set(new StoreScope(
                DataScopeType.STORE, List.of(DemoCatalogIds.STORE), DemoStaffIds.FRONT, null));
        AuthContext.set(JwtPrincipal.staff(
                DemoStaffIds.FRONT, TokenType.F, "STORE", List.of(DemoCatalogIds.STORE)));
        return new Fixture(desk, store, machine, payments, pay);
    }

    private static FrontDeskDtos.WalkInRequest walk(String requestId, String phone, int start) {
        return new FrontDeskDtos.WalkInRequest(
                requestId, phone, "加钟客", null,
                String.valueOf(DemoCatalogIds.THERAPIST_LIN),
                String.valueOf(DemoCatalogIds.PROJECT_P60),
                TODAY, start, true, "CASH", null);
    }

    private static FrontDeskDtos.AddOnRequest addOn(String requestId, int minutes, String channel) {
        return new FrontDeskDtos.AddOnRequest(
                requestId, String.valueOf(DemoCatalogIds.PROJECT_P60), minutes, channel);
    }

    private static Row row(String kind, String check, boolean pass, String detail) {
        return new Row(kind, check, pass, detail);
    }

    private static void screenshot(Path html) {
        Path png = resolveRepoRoot().resolve("docs/test-cases/screenshots/pr-13-addon.png");
        try {
            Files.createDirectories(png.getParent());
            String chrome = firstExisting(
                    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                    "/Applications/Chromium.app/Contents/MacOS/Chromium");
            if (chrome == null) {
                return;
            }
            Process p = new ProcessBuilder(
                    chrome, "--headless=new", "--disable-gpu", "--hide-scrollbars",
                    "--window-size=1280,1600",
                    "--screenshot=" + png.toAbsolutePath(),
                    html.toUri().toString())
                    .redirectErrorStream(true)
                    .start();
            p.waitFor();
        } catch (Exception ignored) {
            // Report HTML is the source of truth; screenshot is best-effort in CI.
        }
    }

    private static String firstExisting(String... paths) {
        for (String path : paths) {
            if (Files.isRegularFile(Path.of(path))) {
                return path;
            }
        }
        return null;
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
                  <title>PR13 前台 · extendOwn 现金 / 微信加钟</title>
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
                    .qr { width:168px; height:168px; margin:8px auto; background:
                          repeating-conic-gradient(#14352c 0% 25%, #fff 0% 50%) 50%/16px 16px;
                          border:4px solid var(--brand); }
                    .hint { font-size:15px; color:#4a6a5f; text-align:center; }
                    code { font-family: ui-monospace, Menlo, monospace; font-size:12px; }
                    footer { padding:0 40px 32px; color:#6b7c75; font-size:12px; }
                  </style>
                </head>
                <body>
                  <header>
                    <h1>PR13 · extendOwn 现金 / 微信加钟</h1>
                    <p>POST /f/orders/{id}/add-on · 40907 冲突 · ADD_ON_PAY_TIMEOUT</p>
                    <span class="badge __CLS__">__BADGE__ · __OK__ / __N__</span>
                  </header>
                  <main>
                    <div class="flow">
                      <div class="st">IN_SERVICE</div>
                      <div class="arr">CASH extendOwn</div>
                      <div class="st pay">fire(ADD_ON)</div>
                      <div class="arr">end_slot += M</div>
                      <div class="st pay">IN_SERVICE</div>
                    </div>
                    <div class="flow">
                      <div class="st">WECHAT LOCKED</div>
                      <div class="arr">Native + poll</div>
                      <div class="st pay">confirmPaidAddOn</div>
                      <div class="arr">fire(ADD_ON)</div>
                      <div class="st pay">不 fire PAY_SUCCESS</div>
                    </div>
                    <div class="callout">
                      <strong>Law A / 40907</strong>
                      <div><code>extendOwn</code> / <code>ReleaseAddOnHold</code> 禁止 <code>fire()</code>。
                      后续格冲突 <code>40907</code> 整单回滚。微信预下单失败 → <code>human_task</code>，锁不回滚。</div>
                    </div>
                    <h2>iPad 前台 1024（加钟 + 收款码）</h2>
                    <div class="ipad" id="ipad-addon">
                      <section class="pad">
                        <h3>服务中加钟</h3>
                        <div class="field"><label>时长</label>
                          <div class="fake">30 分钟 · CASH / WECHAT</div></div>
                        <button class="btn">确认加钟</button>
                        <p class="hint" style="text-align:left;margin-top:16px;">IN_SERVICE · 结束格 +2</p>
                      </section>
                      <section class="pad">
                        <h3>加钟收款码</h3>
                        <div class="qr" aria-label="mock native qr"></div>
                        <p class="hint">WECHAT Native · 轮询 SUCCESS</p>
                        <p class="hint">超时 ADD_ON_PAY_TIMEOUT</p>
                      </section>
                    </div>
                    <h2>验收项</h2>
                    <table>
                      <thead><tr><th>类</th><th>检查</th><th>细节</th><th>结果</th></tr></thead>
                      <tbody>
                __ROWS__      </tbody>
                    </table>
                  </main>
                  <footer>Generated by FrontDeskAddOnReportTest · extendOwn · confirmPaidAddOn</footer>
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
            FrontDeskService desk,
            InMemorySlotOccupyStore store,
            OrderStateMachine machine,
            InMemoryPaymentStore payments,
            PaymentService pay
    ) {
    }
}
