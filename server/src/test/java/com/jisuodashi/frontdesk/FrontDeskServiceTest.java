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
import com.jisuodashi.catalog.DemoCatalogIds;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.PhoneCrypto;
import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.inventory.OccupyFixtures;
import com.jisuodashi.inventory.SlotOccupyService;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrontDeskServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 14);

    private InMemorySlotOccupyStore store;
    private InMemoryCustomerRepository customers;
    private InMemoryPaymentStore payments;
    private FrontDeskService desk;
    private PaymentService pay;
    private PhoneCrypto crypto;

    @BeforeEach
    void setUp() {
        store = OccupyFixtures.demoStore();
        SlotOccupyService occupy = OccupyFixtures.service(store);
        AppClock clock = new AppClock(Clock.fixed(
                TODAY.atTime(LocalTime.of(19, 0)).atZone(AppClock.SHANGHAI).toInstant(),
                AppClock.SHANGHAI));
        AppProperties props = new AppProperties();
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(props);
        OrderStateMachine machine = new OrderStateMachine(store, occupy, clock);
        payments = new InMemoryPaymentStore();
        pay = new PaymentService(payments, store, machine, new MockWeChatPayClient(clock), ids, clock);
        customers = new InMemoryCustomerRepository();
        crypto = new PhoneCrypto(props);
        CustomerMergeService merge = new CustomerMergeService(
                customers,
                new InMemoryRelatedRecordsRepository(ids, clock.clock()),
                new InMemoryAuthSessionRepository(),
                new CollisionTaskWriter(new InMemoryRelatedRecordsRepository(ids, clock.clock())),
                ids,
                clock.clock());
        desk = new FrontDeskService(occupy, store, machine, pay, merge, customers, crypto, clock);
        StoreScopeContext.set(new StoreScope(
                DataScopeType.STORE, List.of(DemoCatalogIds.STORE), DemoStaffIds.FRONT, null));
        AuthContext.set(JwtPrincipal.staff(
                DemoStaffIds.FRONT, TokenType.F, "STORE", List.of(DemoCatalogIds.STORE)));
    }

    @AfterEach
    void clear() {
        StoreScopeContext.clear();
        AuthContext.clear();
    }

    @Test
    void walkInCashMergesPhoneAndChecksIn() {
        FrontDeskDtos.WalkInResponse first = desk.walkIn(walk("svc-cash", "13800138000", 64, true, "CASH"));
        assertThat(first.status()).isEqualTo("CHECKED_IN");
        assertThat(first.payChannel()).isEqualTo("CASH");
        assertThat(first.customerMask()).isEqualTo("138****8000");
        Customer row = customers.findById(Long.parseLong(first.customerId())).orElseThrow();
        assertThat(row.getWxOpenid()).isNull();
        assertThat(row.getPhoneHash()).isEqualTo(crypto.sealMobile("13800138000").hash());

        FrontDeskDtos.WalkInResponse again = desk.walkIn(walk("svc-cash-2", "13800138000", 72, false, "CASH"));
        assertThat(again.customerId()).isEqualTo(first.customerId());
        assertThat(again.status()).isEqualTo("BOOKED");
        assertThat(payments.listByOrderId(Long.parseLong(again.orderId())))
                .anyMatch(p -> Payment.CHANNEL_CASH.equals(p.channel()) && p.success());
    }

    @Test
    void walkInWechatNativeThenNotifyThenCheckIn() {
        FrontDeskDtos.WalkInResponse created = desk.walkIn(walk("svc-wx", "13900139000", 60, true, "WECHAT"));
        assertThat(created.status()).isEqualTo("PENDING_PAY");
        assertThat(created.codeUrl()).startsWith("weixin://wxpay/bizpayurl?pr=MOCK_");
        assertThat(pay.getByPaymentNo(created.paymentNo()).status()).isEqualTo(Payment.PENDING);

        pay.onWechatNotify(body(created.paymentNo(), 19800), Map.of());
        assertThat(store.findOrderById(Long.parseLong(created.orderId())).status()).isEqualTo("BOOKED");
        assertThat(pay.getByPaymentNo(created.paymentNo()).status()).isEqualTo(Payment.SUCCESS);

        FrontDeskDtos.CheckInResponse checked = desk.checkIn(
                created.orderId(),
                new FrontDeskDtos.CheckInRequest("ci", "PHONE", "13900139000"));
        assertThat(checked.status()).isEqualTo("CHECKED_IN");
        assertThat(checked.customerMask()).isEqualTo("139****9000");
    }

    @Test
    void checkInWrongKeywordIs404() {
        FrontDeskDtos.WalkInResponse created = desk.walkIn(walk("svc-kw", "13700137000", 52, true, "CASH"));
        assertThatThrownBy(() -> desk.checkIn(
                created.orderId(),
                new FrontDeskDtos.CheckInRequest("ci-bad", "PHONE", "13600136000")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.NOT_FOUND);
    }

    @Test
    void missingPhoneIs400() {
        assertThatThrownBy(() -> desk.walkIn(new FrontDeskDtos.WalkInRequest(
                "no-phone", " ", "x", null, String.valueOf(DemoCatalogIds.THERAPIST_LIN),
                String.valueOf(DemoCatalogIds.PROJECT_P60), TODAY, 48, true, "CASH", null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.BAD_REQUEST);
    }

    @Test
    void bookedCheckInFromCend() {
        var occupy = OccupyFixtures.service(store);
        var locked = occupy.lockNew(OccupyFixtures.cmd("svc-c", OccupyFixtures.T1, 80));
        new OrderStateMachine(store, occupy, new AppClock(Clock.fixed(
                TODAY.atTime(LocalTime.of(19, 0)).atZone(AppClock.SHANGHAI).toInstant(),
                AppClock.SHANGHAI))).fire(locked.orderId(), OrderEvent.PAY_SUCCESS,
                FireContext.system().withPaymentMatched(true));
        FrontDeskDtos.CheckInResponse checked = desk.checkIn(
                String.valueOf(locked.orderId()),
                new FrontDeskDtos.CheckInRequest("ci-c", "ORDER_NO", locked.orderNo()));
        assertThat(checked.status()).isEqualTo("CHECKED_IN");
        assertThat(checked.roomName()).isEqualTo("一号房");
    }

    private static FrontDeskDtos.WalkInRequest walk(
            String requestId, String phone, int start, boolean already, String channel) {
        return new FrontDeskDtos.WalkInRequest(
                requestId, phone, "散客", null,
                String.valueOf(DemoCatalogIds.THERAPIST_LIN),
                String.valueOf(DemoCatalogIds.PROJECT_P60),
                TODAY, start, already, channel, null);
    }

    private static String body(String paymentNo, long amountFen) {
        return "{\"out_trade_no\":\"" + paymentNo + "\",\"amount_fen\":" + amountFen + "}";
    }
}
