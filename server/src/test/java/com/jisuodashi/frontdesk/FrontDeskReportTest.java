package com.jisuodashi.frontdesk;

import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.auth.CollisionTaskWriter;
import com.jisuodashi.auth.Customer;
import com.jisuodashi.auth.CustomerMergeService;
import com.jisuodashi.auth.DemoStaffIds;
import com.jisuodashi.auth.InMemoryAuthSessionRepository;
import com.jisuodashi.auth.InMemoryCustomerRepository;
import com.jisuodashi.auth.InMemoryRelatedRecordsRepository;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.TokenType;
import com.jisuodashi.catalog.InMemoryCatalogRepository;
import com.jisuodashi.catalog.DemoCatalogIds;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.PhoneCrypto;
import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.inventory.OccupyFixtures;
import com.jisuodashi.inventory.SlotOccupyService;
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

class FrontDeskReportTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 14);

    @Test
    void writeHtmlReport() throws Exception {
        List<Row> rows = new ArrayList<>();
        Fixture cash = fixture();
        FrontDeskDtos.WalkInResponse cashIn = cash.desk.walkIn(walk("rpt-cash", "18600001111", 64, true, "CASH"));
        Customer survivor = cash.customers.findById(Long.parseLong(cashIn.customerId())).orElseThrow();
        rows.add(row("CASH", "散客现金 lockNew → PAY_SUCCESS → 同事务 CHECK_IN",
                "CHECKED_IN".equals(cashIn.status())
                        && cashIn.paymentNo() != null
                        && "186****1111".equals(cashIn.customerMask())
                        && survivor.getWxOpenid() == null
                        && survivor.getPhoneHash() != null,
                "status=" + cashIn.status() + " mask=" + cashIn.customerMask()));

        FrontDeskDtos.WalkInResponse cashAgain = cash.desk.walkIn(walk("rpt-cash-2", "18600001111", 72, false, "CASH"));
        rows.add(row("MERGE", "同一手机 CustomerMerge 复用散客行",
                cashIn.customerId().equals(cashAgain.customerId())
                        && "BOOKED".equals(cashAgain.status())
                        && !cashIn.orderId().equals(cashAgain.orderId()),
                "customer=" + cashIn.customerId() + " orders=" + cashIn.orderId() + "/" + cashAgain.orderId()));

        Fixture wx = fixture();
        FrontDeskDtos.WalkInResponse nativePay = wx.desk.walkIn(walk("rpt-wx", "18600002222", 60, true, "WECHAT"));
        wx.pay.onWechatNotify(
                "{\"out_trade_no\":\"" + nativePay.paymentNo() + "\",\"amount_fen\":19800}", Map.of());
        FrontDeskDtos.CheckInResponse checked = wx.desk.checkIn(
                nativePay.orderId(),
                new FrontDeskDtos.CheckInRequest("rpt-ci", "PHONE", "18600002222"));
        Payment polled = wx.payments.findByPaymentNo(nativePay.paymentNo());
        rows.add(row("WECHAT", "Native 收款码 + GET /f/payments 轮询至 SUCCESS 再核销",
                nativePay.codeUrl() != null && nativePay.codeUrl().contains("LIVE_")
                        && "PENDING_PAY".equals(nativePay.status())
                        && polled != null && polled.success()
                        && "CHECKED_IN".equals(checked.status()),
                "qr=" + nativePay.codeUrl() + " poll=" + (polled == null ? "null" : polled.status())
                        + " checkIn=" + checked.status()));

        Fixture lookup = fixture();
        FrontDeskDtos.WalkInResponse booked = lookup.desk.walkIn(walk("rpt-lu", "18600003333", 52, true, "CASH"));
        FrontDeskDtos.LookupResponse byPhone = lookup.desk.lookup("PHONE", "18600003333");
        FrontDeskDtos.LookupResponse byNo = lookup.desk.lookup("ORDER_NO", booked.orderNo());
        rows.add(row("LOOKUP", "按手机 / 单号查找后 fire(CHECK_IN)",
                byPhone.items().size() == 1
                        && byNo.items().getFirst().orderNo().equals(booked.orderNo())
                        && "CHECKED_IN".equals(booked.status())
                        && "一号房".equals(booked.roomName()),
                "orderNo=" + booked.orderNo() + " room=" + booked.roomName() + " bed=" + booked.bedName()));

        String html = render(rows);
        Path docs = resolveRepoRoot().resolve("docs/test-cases");
        Files.createDirectories(docs);
        Path report = docs.resolve("pr-11-frontdesk.html");
        Files.writeString(report, html, StandardCharsets.UTF_8);
        Files.createDirectories(resolveTargetDir());
        Files.writeString(resolveTargetDir().resolve("pr-11-frontdesk.html"), html, StandardCharsets.UTF_8);

        List<Row> failed = rows.stream().filter(r -> !r.pass).toList();
        assertThat(failed).as("pr-11 report failures: %s", failed).isEmpty();
        assertThat(html).contains("CHECK_IN").contains("WALK_IN").contains("Native");
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
                payments, store, machine, new MockWeChatPayClient(clock), ids, clock);
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
        return new Fixture(desk, customers, payments, pay);
    }

    private static FrontDeskDtos.WalkInRequest walk(
            String requestId, String phone, int start, boolean already, String channel) {
        return new FrontDeskDtos.WalkInRequest(
                requestId, phone, "散客", null,
                String.valueOf(DemoCatalogIds.THERAPIST_LIN),
                String.valueOf(DemoCatalogIds.PROJECT_P60),
                TODAY, start, already, channel, null);
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
                  <title>PR11 前台 · 核销 / 现金 / Native 散客</title>
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
                    .pad { background:#fff; border-radius:12px; padding:16px 18px; min-height:280px; }
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
                    <h1>PR11 · 前台核销 / 现金散客 / WeChat Native</h1>
                    <p>POST /f/orders/{id}/check-in · POST /f/walk-ins · GET /f/payments/{paymentNo}</p>
                    <span class="badge __CLS__">__BADGE__ · __OK__ / __N__</span>
                  </header>
                  <main>
                    <div class="flow">
                      <div class="st">BOOKED</div>
                      <div class="arr">POST /f/orders/{id}/check-in</div>
                      <div class="st pay">fire(CHECK_IN)</div>
                      <div class="arr">→</div>
                      <div class="st pay">CHECKED_IN</div>
                    </div>
                    <div class="flow">
                      <div class="st">CustomerMerge(null, phone)</div>
                      <div class="arr">lockNew WALK_IN</div>
                      <div class="st">CASH → PAY_SUCCESS</div>
                      <div class="arr">alreadyInStore</div>
                      <div class="st pay">CHECK_IN</div>
                    </div>
                    <div class="flow">
                      <div class="st">WECHAT Native QR</div>
                      <div class="arr">poll GET /f/payments</div>
                      <div class="st pay">SUCCESS</div>
                      <div class="arr">再</div>
                      <div class="st pay">CHECK_IN</div>
                    </div>
                    <div class="callout">
                      <strong>D19 / D23</strong>
                      <div>散客必须 11 位手机；禁止手写 <code>phone_hash</code>。微信走 Native 码 +
                      1～2s 轮询，P0 必做。核销 <code>@StoreScoped</code> + <code>frontdesk:order:*</code>。</div>
                    </div>
                    <h2>iPad 前台 1024（核销 + 收款码）</h2>
                    <div class="ipad" id="ipad-desk">
                      <section class="pad">
                        <h3>到店核销</h3>
                        <div class="field"><label>单号 / 手机</label>
                          <div class="fake">JS20260814… / 186****1111</div></div>
                        <button class="btn">核销到店</button>
                        <p class="hint" style="text-align:left;margin-top:16px;">一号房 · 1号床 · CHECKED_IN</p>
                      </section>
                      <section class="pad">
                        <h3>散客收款码</h3>
                        <div class="qr" aria-label="mock native qr"></div>
                        <p class="hint">WECHAT Native · 轮询 SUCCESS</p>
                        <p class="hint">payChannel=CASH | WECHAT</p>
                      </section>
                    </div>
                    <h2>验收项</h2>
                    <table>
                      <thead><tr><th>类</th><th>检查</th><th>细节</th><th>结果</th></tr></thead>
                      <tbody>
                __ROWS__      </tbody>
                    </table>
                  </main>
                  <footer>Generated by FrontDeskReportTest · D19 CustomerMerge · D23 Native + poll</footer>
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
            InMemoryCustomerRepository customers,
            InMemoryPaymentStore payments,
            PaymentService pay
    ) {
    }
}
