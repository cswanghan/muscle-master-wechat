package com.jisuodashi.inventory;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.inventory.InMemorySlotOccupyStore.MutableSlot;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import com.jisuodashi.order.FireContext;
import com.jisuodashi.order.OrderEvent;
import com.jisuodashi.order.OrderStateMachine;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;

import static com.jisuodashi.inventory.OccupyFixtures.BED1;
import static com.jisuodashi.inventory.OccupyFixtures.START_1930;
import static com.jisuodashi.inventory.OccupyFixtures.T1;
import static com.jisuodashi.inventory.OccupyFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtendOwnServiceTest {

    private static final int M = 2;
    private static final long PRO_RATA = 9900L;

    @Test
    void cashExtendOwnPromotesBufferAndTail() {
        Fixture f = inService("eo-cash");
        int occBefore = f.store.occupancyCount();
        long payableBefore = f.store.findOrderById(f.locked.orderId()).payableFen();
        long paidBefore = f.store.paidFen(f.locked.orderId());

        ExtendOwnResult ext = f.occupy.extendOwn(f.locked.orderId(), OccupyFixtures.P60, M, true);

        assertThat(ext.cash()).isTrue();
        assertThat(ext.amountFen()).isEqualTo(PRO_RATA);
        assertThat(ext.newEndSlotNo()).isEqualTo(85);
        assertThat(f.store.therapistSlot(T1, TODAY, 82).status).isEqualTo(SlotStatus.BOOKED);
        assertThat(f.store.therapistSlot(T1, TODAY, 83).status).isEqualTo(SlotStatus.BOOKED);
        assertThat(f.store.therapistSlot(T1, TODAY, 84).status).isEqualTo(SlotStatus.BUFFER);
        assertThat(f.store.bedSlot(BED1, TODAY, 82).status).isEqualTo(SlotStatus.BOOKED);
        assertThat(f.store.bedSlot(BED1, TODAY, 84).status).isEqualTo(SlotStatus.BUFFER);
        assertThat(f.store.occupancyCount()).isEqualTo(occBefore + 2 * M);
        BookingOrderRef after = f.store.findOrderById(f.locked.orderId());
        assertThat(after.endSlotNo()).isEqualTo(85);
        assertThat(after.payableFen()).isEqualTo(payableBefore + PRO_RATA);
        assertThat(f.store.paidFen(f.locked.orderId())).isEqualTo(paidBefore + PRO_RATA);
        assertThat(after.addOnHoldId()).isNull();
        assertThat(f.store.findLatestAddOnItem(f.locked.orderId()).amountFen()).isEqualTo(PRO_RATA);
    }

    @Test
    void wechatExtendOwnLocksAndHolds() {
        Fixture f = inService("eo-wx");
        int endBefore = f.store.findOrderById(f.locked.orderId()).endSlotNo();

        ExtendOwnResult ext = f.occupy.extendOwn(f.locked.orderId(), OccupyFixtures.P60, M, false);

        assertThat(ext.cash()).isFalse();
        assertThat(ext.newEndSlotNo()).isEqualTo(endBefore);
        assertThat(f.store.findOrderById(f.locked.orderId()).endSlotNo()).isEqualTo(endBefore);
        assertThat(f.store.findOrderById(f.locked.orderId()).addOnHoldId()).isEqualTo(ext.addHoldId());
        for (int slot = 82; slot <= 84; slot++) {
            MutableSlot row = f.store.therapistSlot(T1, TODAY, slot);
            assertThat(row.status).isEqualTo(SlotStatus.LOCKED);
            assertThat(row.holdId).isEqualTo(ext.addHoldId());
            assertThat(f.store.bedSlot(BED1, TODAY, slot).status).isEqualTo(SlotStatus.LOCKED);
        }
        InMemorySlotOccupyStore.MutableJob job = f.store.jobByHold(ext.addHoldId());
        assertThat(job).isNotNull();
        assertThat(job.jobType).isEqualTo(SlotOccupyService.JOB_RELEASE_ADDON);
        assertThat(job.status).isEqualTo("PENDING");
        assertThat(job.runAt).isEqualTo(TODAY.atTime(19, 15));
    }

    @Test
    void busyNextSlotsIs40907NoPartial() {
        Fixture f = inService("eo-busy");
        f.store.therapistSlot(T1, TODAY, 83).status = SlotStatus.BOOKED;
        int occBefore = f.store.occupancyCount();

        assertThatThrownBy(() -> f.occupy.extendOwn(f.locked.orderId(), OccupyFixtures.P60, M, true))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.ADD_ON_CONFLICT);

        assertThat(f.store.occupancyCount()).isEqualTo(occBefore);
        assertThat(f.store.therapistSlot(T1, TODAY, 82).status).isEqualTo(SlotStatus.BUFFER);
        assertThat(f.store.therapistSlot(T1, TODAY, 83).status).isEqualTo(SlotStatus.BOOKED);
        assertThat(f.store.therapistSlot(T1, TODAY, 84).status).isEqualTo(SlotStatus.FREE);
        assertThat(f.store.findOrderById(f.locked.orderId()).endSlotNo()).isEqualTo(83);
        assertThat(f.store.findOrderById(f.locked.orderId()).addOnHoldId()).isNull();
        assertThat(f.store.findLatestAddOnItem(f.locked.orderId())).isNull();
    }

    @Test
    void secondAddOnWhileUnpaidHoldIs40904() {
        Fixture f = inService("eo-second");
        f.occupy.extendOwn(f.locked.orderId(), OccupyFixtures.P60, M, false);
        assertThatThrownBy(() -> f.occupy.extendOwn(f.locked.orderId(), OccupyFixtures.P60, 1, true))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.ILLEGAL_TRANSITION);
        assertThat(f.store.findOrderById(f.locked.orderId()).addOnHoldId()).isNotNull();
    }

    @Test
    void notInServiceIs40904() {
        Fixture f = fixture("eo-booked");
        f.occupy.confirmPaidSlots(f.locked.orderId());
        f.store.setOrderStatus(f.locked.orderId(), "BOOKED");
        assertThatThrownBy(() -> f.occupy.extendOwn(f.locked.orderId(), OccupyFixtures.P60, M, true))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.ILLEGAL_TRANSITION);
    }

    @Test
    void addOnPayTimeoutRestoresBuffer() {
        Fixture f = inService("eo-timeout");
        ExtendOwnResult ext = f.occupy.extendOwn(f.locked.orderId(), OccupyFixtures.P60, M, false);
        f.machine.fire(f.locked.orderId(), OrderEvent.ADD_ON_PAY_TIMEOUT, FireContext.job());

        BookingOrderRef after = f.store.findOrderById(f.locked.orderId());
        assertThat(after.status()).isEqualTo("IN_SERVICE");
        assertThat(after.addOnHoldId()).isNull();
        assertThat(after.endSlotNo()).isEqualTo(83);
        assertThat(f.store.therapistSlot(T1, TODAY, 82).status).isEqualTo(SlotStatus.BUFFER);
        assertThat(f.store.therapistSlot(T1, TODAY, 82).holdId).isEqualTo(f.locked.holdId());
        assertThat(f.store.therapistSlot(T1, TODAY, 83).status).isEqualTo(SlotStatus.FREE);
        assertThat(f.store.therapistSlot(T1, TODAY, 84).status).isEqualTo(SlotStatus.FREE);
        assertThat(f.store.findLatestAddOnItem(f.locked.orderId())).isNull();
        assertThat(ext.addHoldId()).isPositive();
    }

    @Test
    void releaseAddOnHoldKeepsPaidCashItem() {
        Fixture f = inService("eo-cash-then-wx");
        f.occupy.extendOwn(f.locked.orderId(), OccupyFixtures.P60, M, true);
        f.occupy.extendOwn(f.locked.orderId(), OccupyFixtures.P60, 1, false);
        f.machine.fire(f.locked.orderId(), OrderEvent.ADD_ON_PAY_TIMEOUT, FireContext.job());

        SlotOccupyStore.OrderItemInsert paid = f.store.findLatestAddOnItem(f.locked.orderId());
        assertThat(paid).isNotNull();
        assertThat(paid.startSlotNo()).isEqualTo(82);
        assertThat(paid.endSlotNo()).isEqualTo(85);
        assertThat(paid.amountFen()).isEqualTo(PRO_RATA);
        assertThat(f.store.orderItems.stream().filter(i -> "ADD_ON".equals(i.itemType()))).hasSize(1);
        assertThat(f.store.findOrderById(f.locked.orderId()).endSlotNo()).isEqualTo(85);
        assertThat(f.store.findOrderById(f.locked.orderId()).addOnHoldId()).isNull();
    }

    @Test
    void confirmPaidAddOnThenFirePromotesSlots() {
        Fixture f = inService("eo-paid");
        ExtendOwnResult ext = f.occupy.extendOwn(f.locked.orderId(), OccupyFixtures.P60, M, false);
        ConfirmPaidResult paid = f.occupy.confirmPaidAddOn(f.locked.orderId());
        f.machine.fire(f.locked.orderId(), OrderEvent.ADD_ON, FireContext.system().withAddOnPaid());

        assertThat(paid.therapistUpdated()).isEqualTo(3);
        assertThat(paid.bedUpdated()).isEqualTo(3);
        assertThat(f.store.jobByHold(ext.addHoldId()).status).isEqualTo("DONE");
        BookingOrderRef after = f.store.findOrderById(f.locked.orderId());
        assertThat(after.status()).isEqualTo("IN_SERVICE");
        assertThat(after.endSlotNo()).isEqualTo(85);
        assertThat(after.addOnHoldId()).isNull();
        assertThat(after.payableFen()).isEqualTo(19800 + PRO_RATA);
        assertThat(f.store.paidFen(f.locked.orderId())).isEqualTo(PRO_RATA);
        assertThat(f.store.therapistSlot(T1, TODAY, 82).status).isEqualTo(SlotStatus.BOOKED);
        assertThat(f.store.therapistSlot(T1, TODAY, 83).status).isEqualTo(SlotStatus.BOOKED);
        assertThat(f.store.therapistSlot(T1, TODAY, 84).status).isEqualTo(SlotStatus.BUFFER);
        assertThat(f.store.therapistSlot(T1, TODAY, 84).lockExpireAt).isNull();
    }

    private static Fixture inService(String requestId) {
        Fixture f = fixture(requestId);
        f.occupy.confirmPaidSlots(f.locked.orderId());
        f.store.setOrderStatus(f.locked.orderId(), "IN_SERVICE");
        return f;
    }

    private static Fixture fixture(String requestId) {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService occupy = OccupyFixtures.service(store);
        AppClock clock = new AppClock(Clock.fixed(
                LocalDate.of(2026, 8, 14).atTime(LocalTime.of(19, 0)).atZone(AppClock.SHANGHAI).toInstant(),
                AppClock.SHANGHAI));
        OrderStateMachine machine = new OrderStateMachine(store, occupy, clock);
        LockNewResult locked = occupy.lockNew(OccupyFixtures.cmd(requestId, T1, START_1930));
        return new Fixture(store, occupy, machine, locked);
    }

    private record Fixture(
            InMemorySlotOccupyStore store,
            SlotOccupyService occupy,
            OrderStateMachine machine,
            LockNewResult locked
    ) {
    }
}
