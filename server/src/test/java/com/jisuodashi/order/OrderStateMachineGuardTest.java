package com.jisuodashi.order;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.inventory.LockNewResult;
import com.jisuodashi.inventory.OccupyFixtures;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.inventory.SlotStatus;
import com.jisuodashi.rbac.InMemoryAuditLogRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderStateMachineGuardTest {

    @Test
    void cancelInsideWindowIs40904AndWritesAudit() {
        Fixture f = fixture("grd-cancel");
        f.machine.fire(f.locked.orderId(), OrderEvent.PAY_SUCCESS, FireContext.job().withPaymentMatched(true));
        assertThatThrownBy(() -> f.machine.fire(
                f.locked.orderId(), OrderEvent.CANCEL, FireContext.customer(OccupyFixtures.CUSTOMER)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.ILLEGAL_TRANSITION);
        assertThat(f.store.findOrderByHoldId(f.locked.holdId()).status()).isEqualTo("BOOKED");
        assertThat(f.audits.listRecent(5))
                .anyMatch(e -> "ILLEGAL_TRANSITION".equals(e.getAction())
                        && e.getAfterJson() != null
                        && e.getAfterJson().contains("cancel-window"));
    }

    @Test
    void paySuccessWithoutMatchIs40904() {
        Fixture f = fixture("grd-pay");
        assertThatThrownBy(() -> f.machine.fire(
                f.locked.orderId(), OrderEvent.PAY_SUCCESS,
                FireContext.customer(OccupyFixtures.CUSTOMER).withPaymentMatched(false)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(40904);
        assertThat(f.store.findOrderByHoldId(f.locked.holdId()).status()).isEqualTo("PENDING_PAY");
        assertThat(f.audits.listRecent(5))
                .anyMatch(e -> e.getAfterJson() != null && e.getAfterJson().contains("payment-mismatch"));
    }

    @Test
    void checkInWrongStoreIs40904() {
        Fixture f = fixture("grd-scope");
        f.machine.fire(f.locked.orderId(), OrderEvent.PAY_SUCCESS, FireContext.job().withPaymentMatched(true));
        assertThatThrownBy(() -> f.machine.fire(
                f.locked.orderId(), OrderEvent.CHECK_IN,
                FireContext.staff(9L, List.of(99L)).withFrontDesk()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(40904);
        assertThat(f.audits.listRecent(5))
                .anyMatch(e -> e.getAfterJson() != null && e.getAfterJson().contains("store-scope"));
    }

    @Test
    void markNoShowBeforeGraceIs40904() {
        Fixture f = fixture("grd-noshow");
        f.machine.fire(f.locked.orderId(), OrderEvent.PAY_SUCCESS, FireContext.job().withPaymentMatched(true));
        assertThatThrownBy(() -> f.machine.fire(f.locked.orderId(), OrderEvent.MARK_NO_SHOW, FireContext.system()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(40904);
        assertThat(f.audits.listRecent(5))
                .anyMatch(e -> e.getAfterJson() != null && e.getAfterJson().contains("too-early-no-show"));
    }

    @Test
    void cancelFreeMinutesIsReadFromProperties() {
        AppProperties props = new AppProperties();
        assertThat(props.getBooking().getCancelFreeMinutes()).isEqualTo(120);
        Fixture f = fixture("grd-cfg");
        f.machine.fire(f.locked.orderId(), OrderEvent.PAY_SUCCESS, FireContext.job().withPaymentMatched(true));
        String fail = f.machine.guardFailure(
                OrderStatus.BOOKED, OrderEvent.CANCEL,
                f.store.lockOrderById(f.locked.orderId()),
                FireContext.customer(OccupyFixtures.CUSTOMER));
        assertThat(fail).isEqualTo("cancel-window");
    }

    @Test
    void startServiceWrongTherapistIs40904() {
        Fixture f = fixture("grd-t");
        f.machine.fire(f.locked.orderId(), OrderEvent.PAY_SUCCESS, FireContext.job().withPaymentMatched(true));
        f.machine.fire(f.locked.orderId(), OrderEvent.CHECK_IN, FireContext.system());
        assertThatThrownBy(() -> f.machine.fire(
                f.locked.orderId(), OrderEvent.START_SERVICE, FireContext.staff(1L, List.of(OccupyFixtures.STORE))))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(40904);
    }

    @Test
    void releaseAddOnHoldRestoresBufferAndDropsTail() {
        Fixture f = fixture("grd-addon");
        f.machine.fire(f.locked.orderId(), OrderEvent.PAY_SUCCESS, FireContext.job().withPaymentMatched(true));
        long addHold = 7_700_000_000_000_000_088L;
        f.store.plantAddOnTail(
                f.locked.orderId(), OccupyFixtures.T1, OccupyFixtures.BED1,
                LocalDate.of(2026, 8, 14), 83, addHold,
                LocalDate.of(2026, 8, 14).atTime(19, 0));
        assertThat(f.store.findOrderByHoldId(f.locked.holdId()).addOnHoldId()).isEqualTo(addHold);

        f.occupy.releaseAddOnHold(addHold);

        assertThat(f.store.findOrderByHoldId(f.locked.holdId()).addOnHoldId()).isNull();
        assertThat(f.store.therapistSlot(OccupyFixtures.T1, LocalDate.of(2026, 8, 14), 83).status)
                .isEqualTo(SlotStatus.FREE);
        assertThat(f.store.therapistSlot(OccupyFixtures.T1, LocalDate.of(2026, 8, 14), 82).status)
                .isEqualTo(SlotStatus.BUFFER);
        assertThat(f.store.therapistSlot(OccupyFixtures.T1, LocalDate.of(2026, 8, 14), 82).holdId)
                .isEqualTo(f.locked.holdId());
        assertThat(f.store.orderItems.stream().noneMatch(i -> "ADD_ON".equals(i.itemType()))).isTrue();
    }

    private static Fixture fixture(String requestId) {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService occupy = OccupyFixtures.service(store);
        AppClock clock = new AppClock(Clock.fixed(
                LocalDate.of(2026, 8, 14).atTime(LocalTime.of(19, 0)).atZone(AppClock.SHANGHAI).toInstant(),
                AppClock.SHANGHAI));
        InMemoryAuditLogRepository audits = new InMemoryAuditLogRepository();
        AppProperties props = new AppProperties();
        OrderStateMachine machine = new OrderStateMachine(
                store, occupy, clock, props, audits, new SnowflakeIdGenerator(props));
        LockNewResult locked = occupy.lockNew(OccupyFixtures.cmd(requestId, OccupyFixtures.T1, OccupyFixtures.START_1930));
        return new Fixture(store, occupy, machine, locked, audits);
    }

    private record Fixture(
            InMemorySlotOccupyStore store,
            SlotOccupyService occupy,
            OrderStateMachine machine,
            LockNewResult locked,
            InMemoryAuditLogRepository audits
    ) {
    }
}
