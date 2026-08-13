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
import com.jisuodashi.inventory.LockNewResult;
import com.jisuodashi.inventory.OccupyFixtures;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.order.FireContext;
import com.jisuodashi.order.OrderEvent;
import com.jisuodashi.order.OrderStateMachine;
import com.jisuodashi.payment.InMemoryPaymentStore;
import com.jisuodashi.payment.MockWeChatPayClient;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrontDeskRescheduleServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 14);

    private InMemorySlotOccupyStore store;
    private SlotOccupyService occupy;
    private FrontDeskService desk;
    private OrderStateMachine machine;

    @BeforeEach
    void setUp() {
        store = OccupyFixtures.demoStore();
        occupy = OccupyFixtures.service(store);
        AppClock clock = new AppClock(Clock.fixed(
                TODAY.atTime(LocalTime.of(19, 0)).atZone(AppClock.SHANGHAI).toInstant(),
                AppClock.SHANGHAI));
        AppProperties props = new AppProperties();
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(props);
        machine = new OrderStateMachine(store, occupy, clock);
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
        desk = new FrontDeskService(
                occupy, store, machine, pay, merge, customers, crypto, clock, new InMemoryCatalogRepository());
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
    void deskRescheduleFiresBookedToBookedWithoutReleaseLock() {
        LockNewResult locked = occupy.lockNew(OccupyFixtures.cmd("desk-rs", OccupyFixtures.T1, 64));
        machine.fire(locked.orderId(), OrderEvent.PAY_SUCCESS, FireContext.system().withPaymentMatched(true));
        long oldHold = store.findOrderById(locked.orderId()).holdId();

        FrontDeskDtos.RescheduleResponse moved = desk.reschedule(
                String.valueOf(locked.orderId()),
                new FrontDeskDtos.RescheduleRequest(
                        "desk-rs-1", TODAY, 72, String.valueOf(DemoCatalogIds.THERAPIST_CHEN)));

        assertThat(moved.status()).isEqualTo("BOOKED");
        assertThat(moved.startSlotNo()).isEqualTo(72);
        assertThat(moved.therapistId()).isEqualTo(String.valueOf(DemoCatalogIds.THERAPIST_CHEN));
        assertThat(moved.replay()).isFalse();
        assertThat(store.findOrderById(locked.orderId()).status()).isEqualTo("BOOKED");
        assertThat(store.findOrderById(locked.orderId()).holdId()).isNotEqualTo(oldHold);
        assertThat(store.jobByHold(oldHold).status).isEqualTo("DONE");
        assertThat(store.jobByHold(store.findOrderById(locked.orderId()).holdId())).isNull();
        assertThat(store.changeLogs).extracting(c -> c.changeType()).containsExactly("RESCHEDULE");
    }

    @Test
    void pendingPayCannotReschedule() {
        LockNewResult locked = occupy.lockNew(OccupyFixtures.cmd("desk-rs-p", OccupyFixtures.T1, 64));
        assertThatThrownBy(() -> desk.reschedule(
                String.valueOf(locked.orderId()),
                new FrontDeskDtos.RescheduleRequest(
                        "desk-rs-p1", TODAY, 72, String.valueOf(DemoCatalogIds.THERAPIST_LIN))))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.ILLEGAL_TRANSITION);
        assertThat(store.findOrderById(locked.orderId()).startSlotNo()).isEqualTo(64);
    }
}
