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
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.PhoneCrypto;
import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.inventory.InMemoryTherapistDayLock;
import com.jisuodashi.inventory.OccupyFixtures;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import com.jisuodashi.inventory.SlotStatus;
import com.jisuodashi.inventory.SwapTherapistResult;
import com.jisuodashi.order.FireContext;
import com.jisuodashi.order.OrderEvent;
import com.jisuodashi.order.OrderStateMachine;
import com.jisuodashi.payment.InMemoryPaymentStore;
import com.jisuodashi.payment.MockWeChatPayClient;
import com.jisuodashi.payment.PaymentService;
import com.jisuodashi.rbac.DataScopeType;
import com.jisuodashi.rbac.StoreScope;
import com.jisuodashi.rbac.StoreScopeContext;
import com.jisuodashi.staff.InMemoryTreatmentNoteRepository;
import com.jisuodashi.staff.ServiceRecord;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class FrontDeskSwapReportTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 14);

    @Test
    void writeHtmlReport() throws Exception {
        List<Row> rows = new ArrayList<>();
        Fixture ci = fixture(LocalTime.of(19, 0));
        FrontDeskDtos.WalkInResponse walk = ci.desk.walkIn(walk("rpt-sw-ci", "18600001111", 64, true, "CASH"));
        FrontDeskDtos.SwapTherapistResponse swapped = ci.desk.swapTherapist(
                walk.orderId(),
                new FrontDeskDtos.SwapTherapistRequest(
                        "rpt-sw-1", String.valueOf(DemoCatalogIds.THERAPIST_CHEN), "指定技师请假"));
        long orderId = Long.parseLong(walk.orderId());
        rows.add(row("CHECKED_IN", "remain=[start,end) 新技师接管 · 旧格 FREE · 床不动",
                "CHECKED_IN".equals(swapped.status())
                        && swapped.fromSlotNo() == 64
                        && ci.store.findOrderById(orderId).therapistId() == DemoCatalogIds.THERAPIST_CHEN
                        && SlotStatus.FREE.equals(
                        ci.store.therapistSlot(DemoCatalogIds.THERAPIST_LIN, TODAY, 64).status)
                        && SlotStatus.BOOKED.equals(
                        ci.store.therapistSlot(DemoCatalogIds.THERAPIST_CHEN, TODAY, 64).status)
                        && ci.store.occupancies.values().stream().anyMatch(o ->
                        "BED".equals(o.resourceType()) && o.orderId() == orderId),
                "status=" + swapped.status() + " from=" + swapped.fromSlotNo()
                        + " new=" + swapped.newTherapistId()));

        FrontDeskDtos.SwapTherapistResponse replay = ci.desk.swapTherapist(
                walk.orderId(),
                new FrontDeskDtos.SwapTherapistRequest(
                        "rpt-sw-1", String.valueOf(DemoCatalogIds.THERAPIST_CHEN), "指定技师请假"));
        rows.add(row("IDEM", "同一 requestId 回放",
                replay.replay() && replay.orderId().equals(swapped.orderId()),
                "replay=" + replay.replay()));

        Fixture busy = fixture(LocalTime.of(19, 0));
        FrontDeskDtos.WalkInResponse busyOrder = busy.desk.walkIn(walk("rpt-sw-busy", "18600002222", 72, true, "CASH"));
        busy.store.therapistSlot(DemoCatalogIds.THERAPIST_CHEN, TODAY, 73).status = SlotStatus.BOOKED;
        Throwable busyEx = catchThrowable(() -> busy.desk.swapTherapist(
                busyOrder.orderId(),
                new FrontDeskDtos.SwapTherapistRequest(
                        "rpt-sw-busy-1", String.valueOf(DemoCatalogIds.THERAPIST_CHEN), "x")));
        rows.add(row("40901", "新技师占用 → 40901 无部分更新",
                busyEx instanceof com.jisuodashi.common.ApiException api
                        && api.getCode() == ErrorCodes.SLOT_UNAVAILABLE
                        && busy.store.findOrderById(Long.parseLong(busyOrder.orderId())).therapistId()
                        == DemoCatalogIds.THERAPIST_LIN,
                "code=" + (busyEx instanceof com.jisuodashi.common.ApiException api
                        ? api.getCode() : busyEx)));

        Fixture bad = fixture(LocalTime.of(19, 0));
        FrontDeskDtos.WalkInResponse booked = bad.desk.walkIn(walk("rpt-sw-bk", "18600003333", 52, false, "CASH"));
        Throwable bookedEx = catchThrowable(() -> bad.desk.swapTherapist(
                booked.orderId(),
                new FrontDeskDtos.SwapTherapistRequest(
                        "rpt-sw-bk-1", String.valueOf(DemoCatalogIds.THERAPIST_CHEN), "x")));
        rows.add(row("40904", "BOOKED / PENDING_PAY 不可换师",
                bookedEx instanceof com.jisuodashi.common.ApiException api
                        && api.getCode() == ErrorCodes.ILLEGAL_TRANSITION
                        && "BOOKED".equals(booked.status()),
                "status=" + booked.status()));

        Fixture mid = fixture(LocalTime.of(20, 0));
        var locked = mid.occupy.lockNew(OccupyFixtures.cmd("rpt-sw-is", DemoCatalogIds.THERAPIST_LIN, 78));
        mid.machine.fire(locked.orderId(), OrderEvent.PAY_SUCCESS);
        mid.machine.fire(locked.orderId(), OrderEvent.CHECK_IN, FireContext.system().withFrontDesk());
        mid.machine.fire(locked.orderId(), OrderEvent.START_SERVICE);
        mid.notes.insertServiceRecord(
                9L, locked.orderId(), DemoCatalogIds.THERAPIST_LIN, OccupyFixtures.CUSTOMER, OccupyFixtures.STORE,
                TODAY.atTime(19, 30).atZone(AppClock.SHANGHAI).toInstant());
        SwapTherapistResult inSvc = mid.occupy.swapTherapist(
                "rpt-sw-is-1", locked.orderId(), DemoCatalogIds.THERAPIST_CHEN, "中途换师");
        mid.machine.fire(locked.orderId(), OrderEvent.SWAP_THERAPIST, FireContext.system().withSwapOk());
        List<ServiceRecord> segs = mid.notes.listServiceRecords(locked.orderId());
        rows.add(row("IN_SERVICE", "fromNo=currentSlotNo · 过去格留旧师 · 新 service_record 段",
                inSvc.fromSlotNo() == 80
                        && SlotStatus.BOOKED.equals(mid.store.therapistSlot(DemoCatalogIds.THERAPIST_LIN, TODAY, 78).status)
                        && SlotStatus.FREE.equals(mid.store.therapistSlot(DemoCatalogIds.THERAPIST_LIN, TODAY, 80).status)
                        && segs.size() == 2
                        && segs.get(0).endedAt() != null
                        && segs.get(1).therapistId() == DemoCatalogIds.THERAPIST_CHEN
                        && segs.get(1).endedAt() == null,
                "from=" + inSvc.fromSlotNo() + " segs=" + segs.size()));

        BookingOrderRef inSvcRef = new BookingOrderRef(
                1L, "n", 1L, 1L, 1L, "IN_SERVICE", null, 0L, 78, 83, 1, null,
                DemoCatalogIds.STORE, TODAY, 1L, DemoCatalogIds.THERAPIST_LIN);
        int afterEnd = SlotOccupyService.remainFrom(inSvcRef, TODAY.atTime(21, 0));
        int nextMorning = SlotOccupyService.remainFrom(inSvcRef, TODAY.plusDays(1).atTime(8, 0));
        rows.add(row("OVERNIGHT", "跨日 / 结束后 fromNo=end，不回退到 start",
                afterEnd == 83 && nextMorning == 83,
                "afterEnd=" + afterEnd + " nextMorning=" + nextMorning));

        String html = render(rows);
        Path docs = resolveRepoRoot().resolve("docs/test-cases");
        Files.createDirectories(docs);
        Path report = docs.resolve("pr-14-swap.html");
        Files.writeString(report, html, StandardCharsets.UTF_8);
        Files.createDirectories(resolveTargetDir());
        Files.writeString(resolveTargetDir().resolve("pr-14-swap.html"), html, StandardCharsets.UTF_8);

        List<Row> failed = rows.stream().filter(r -> !r.pass).toList();
        assertThat(failed).as("pr-14 report failures: %s", failed).isEmpty();
        assertThat(html).contains("SWAP_THERAPIST").contains("service_record");
        assertThat(report).exists();
        StoreScopeContext.clear();
        AuthContext.clear();
    }

    private static Fixture fixture(LocalTime time) {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        AppClock clock = new AppClock(Clock.fixed(
                TODAY.atTime(time).atZone(AppClock.SHANGHAI).toInstant(), AppClock.SHANGHAI));
        AtomicLong ids = new AtomicLong(9_200_000_000_000_000_000L);
        SlotOccupyService occupy = new SlotOccupyService(
                store, new InMemoryTherapistDayLock(), ids::incrementAndGet, clock);
        InMemoryTreatmentNoteRepository notes = new InMemoryTreatmentNoteRepository();
        occupy.setTreatmentNotes(notes);
        AppProperties props = new AppProperties();
        SnowflakeIdGenerator snow = new SnowflakeIdGenerator(props);
        OrderStateMachine machine = new OrderStateMachine(store, occupy, clock);
        InMemoryPaymentStore payments = new InMemoryPaymentStore();
        PaymentService pay = new PaymentService(
                payments, store, machine, new MockWeChatPayClient(clock), snow, clock);
        InMemoryCustomerRepository customers = new InMemoryCustomerRepository();
        PhoneCrypto crypto = new PhoneCrypto(props);
        CustomerMergeService merge = new CustomerMergeService(
                customers,
                new InMemoryRelatedRecordsRepository(snow, clock.clock()),
                new InMemoryAuthSessionRepository(),
                new CollisionTaskWriter(new InMemoryRelatedRecordsRepository(snow, clock.clock())),
                snow,
                clock.clock());
        FrontDeskService desk = new FrontDeskService(
                occupy, store, machine, pay, merge, customers, crypto, clock, new InMemoryCatalogRepository());
        StoreScopeContext.set(new StoreScope(
                DataScopeType.STORE, List.of(DemoCatalogIds.STORE), DemoStaffIds.FRONT, null));
        AuthContext.set(JwtPrincipal.staff(
                DemoStaffIds.FRONT, TokenType.F, "STORE", List.of(DemoCatalogIds.STORE)));
        return new Fixture(desk, occupy, store, notes, machine);
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
                  <title>PR14 换技师 · swapTherapist</title>
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
                    .st.act { background:#1E5C4A; color:#fff; }
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
                    code { font-family: ui-monospace, Menlo, monospace; font-size:12px; }
                    footer { padding:0 40px 32px; color:#6b7c75; font-size:12px; }
                  </style>
                </head>
                <body>
                  <header>
                    <h1>PR14 · 换技师 swapTherapist</h1>
                    <p>POST /f/orders/{id}/swap-therapist · 只锁新技师 remain · 不重锁本单床 · 多段 service_record</p>
                    <span class="badge __CLS__">__BADGE__ · __OK__ / __N__</span>
                  </header>
                  <main>
                    <div class="flow">
                      <div class="st">CHECKED_IN / IN_SERVICE</div>
                      <div class="arr">occupy.swapTherapist</div>
                      <div class="st act">remain → 新技师</div>
                      <div class="arr">fire(SWAP_THERAPIST, swapOk)</div>
                      <div class="st">同状态 + 审计</div>
                    </div>
                    <div class="callout">
                      <strong>Law A / 路径</strong>
                      <div>库存与多段记录在 <code>SlotOccupyService.swapTherapist</code> 完成，禁止
                      <code>fire()</code>。前台 API 成功后再
                      <code>fire(SWAP_THERAPIST)</code> 且 <code>FireContext.swapOk=true</code>。
                      状态机 <code>SWAP_THERAPIST</code> 副作用为空操作。</div>
                    </div>
                    <h2>iPad 前台 1024（换技师）</h2>
                    <div class="ipad" id="ipad-swap">
                      <section class="pad">
                        <h3>到店核销</h3>
                        <div class="field"><label>单号 / 手机</label>
                          <div class="fake">JS20260814… / 186****1111</div></div>
                        <button class="btn">核销到店</button>
                        <p style="font-size:15px;color:#4a6a5f;margin-top:16px;">CHECKED_IN · 一号房 · 1号床</p>
                      </section>
                      <section class="pad">
                        <h3>换技师</h3>
                        <div class="field"><label>新技师</label>
                          <div class="fake">陈默 · 3100…402</div></div>
                        <div class="field"><label>原因</label>
                          <div class="fake">指定技师请假</div></div>
                        <button class="btn">确认换师</button>
                      </section>
                    </div>
                    <h2>验收项</h2>
                    <table>
                      <thead><tr><th>类</th><th>检查</th><th>细节</th><th>结果</th></tr></thead>
                      <tbody>
                __ROWS__      </tbody>
                    </table>
                  </main>
                  <footer>Generated by FrontDeskSwapReportTest · occupy then fire(SWAP_THERAPIST)</footer>
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
            SlotOccupyService occupy,
            InMemorySlotOccupyStore store,
            InMemoryTreatmentNoteRepository notes,
            OrderStateMachine machine
    ) {
    }
}
