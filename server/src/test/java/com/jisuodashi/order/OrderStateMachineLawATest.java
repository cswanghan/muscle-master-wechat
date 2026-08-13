package com.jisuodashi.order;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.inventory.LockNewResult;
import com.jisuodashi.inventory.OccupyFixtures;
import com.jisuodashi.inventory.ReleaseResult;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.inventory.SlotStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderStateMachineLawATest {

    @Test
    void payTimeoutWritesClosedThenReleaseLock() {
        Fixture f = fixture("law-timeout");
        FireResult fired = f.machine.fire(f.locked.orderId(), OrderEvent.PAY_TIMEOUT);
        assertThat(fired.to()).isEqualTo(OrderStatus.CLOSED);
        assertThat(f.store.findOrderByHoldId(f.locked.holdId()).status()).isEqualTo("CLOSED");
        assertThat(f.store.occupancies).isEmpty();
        assertThat(f.store.therapistSlot(OccupyFixtures.T1, LocalDate.of(2026, 8, 14), OccupyFixtures.START_1930)
                .status).isEqualTo(SlotStatus.FREE);
    }

    @Test
    void userCancelReleasesLockWithoutRecursingFire() {
        Fixture f = fixture("law-cancel");
        f.machine.fire(f.locked.orderId(), OrderEvent.USER_CANCEL);
        assertThat(f.store.findOrderByHoldId(f.locked.holdId()).status()).isEqualTo("CLOSED");
        assertThat(f.store.occupancies).isEmpty();
        assertThatThrownBy(() -> f.machine.fire(f.locked.orderId(), OrderEvent.USER_CANCEL))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.ILLEGAL_TRANSITION);
    }

    @Test
    void releaseLockMustNotFire() {
        Fixture f = fixture("law-release-only");
        ReleaseResult released = f.occupy.releaseLock(f.locked.holdId());
        assertThat(released.freed() || ReleaseResult.IDEMPOTENT.equals(released.outcome())
                || ReleaseResult.FREED.equals(released.outcome())).isTrue();
        assertThat(f.store.findOrderByHoldId(f.locked.holdId()).status())
                .isEqualTo(SlotOccupyService.ORDER_PENDING_PAY);
    }

    @Test
    void paySuccessConfirmsSlotsAndMarksJobDone() {
        Fixture f = fixture("law-pay");
        FireResult fired = f.machine.fire(f.locked.orderId(), OrderEvent.PAY_SUCCESS);
        assertThat(fired.to()).isEqualTo(OrderStatus.BOOKED);
        assertThat(f.store.findOrderByHoldId(f.locked.holdId()).status()).isEqualTo("BOOKED");
        assertThat(f.store.therapistSlot(OccupyFixtures.T1, LocalDate.of(2026, 8, 14), OccupyFixtures.START_1930)
                .status).isEqualTo(SlotStatus.BOOKED);
        assertThat(f.store.therapistSlot(OccupyFixtures.T1, LocalDate.of(2026, 8, 14), 82)
                .status).isEqualTo(SlotStatus.BUFFER);
        assertThat(f.store.jobByHold(f.locked.holdId()).status).isEqualTo("DONE");
    }

    @Test
    void bookedThenPayTimeoutIs40904AndKeepsOccupancy() {
        Fixture f = fixture("law-late-timeout");
        f.machine.fire(f.locked.orderId(), OrderEvent.PAY_SUCCESS);
        assertThatThrownBy(() -> f.machine.fire(f.locked.orderId(), OrderEvent.PAY_TIMEOUT))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.ILLEGAL_TRANSITION);
        assertThat(f.store.findOrderByHoldId(f.locked.holdId()).status()).isEqualTo("BOOKED");
        assertThat(f.store.occupancies).hasSize(10);
    }

    @Test
    void checkInStartCompleteHappyPath() {
        Fixture f = fixture("law-happy");
        f.machine.fire(f.locked.orderId(), OrderEvent.PAY_SUCCESS);
        assertThat(f.machine.fire(f.locked.orderId(), OrderEvent.CHECK_IN).to()).isEqualTo(OrderStatus.CHECKED_IN);
        assertThat(f.machine.fire(f.locked.orderId(), OrderEvent.START_SERVICE).to()).isEqualTo(OrderStatus.IN_SERVICE);
        assertThat(f.machine.fire(f.locked.orderId(), OrderEvent.COMPLETE_SERVICE).to()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void releaseStarSourceDoesNotCallFire() throws Exception {
        Path file = Path.of("src/main/java/com/jisuodashi/inventory/SlotOccupyService.java");
        if (!Files.isRegularFile(file)) {
            file = Path.of("server/src/main/java/com/jisuodashi/inventory/SlotOccupyService.java");
        }
        String src = Files.readString(file);
        assertThat(src).contains("MUST NOT");
        assertThat(src).doesNotContain("import com.jisuodashi.order");
        assertThat(src).doesNotContain("OrderStateMachine");
        assertThat(src).doesNotContain("orderStateMachine");
        Path machine = Path.of("src/main/java/com/jisuodashi/order/OrderStateMachine.java");
        if (!Files.isRegularFile(machine)) {
            machine = Path.of("server/src/main/java/com/jisuodashi/order/OrderStateMachine.java");
        }
        String fireSrc = Files.readString(machine);
        assertThat(fireSrc).contains("confirmPaidSlotsInOpenTx");
        assertThat(fireSrc).contains("releaseLockInOpenTx");
        assertThat(fireSrc).doesNotContain("occupy.releaseLock(");
        assertThat(fireSrc).doesNotContain("occupy.confirmPaidSlots(");
        assertThat(extractMethod(src, "private ReleaseResult doReleaseLock")).doesNotContain(".fire(");
        assertThat(extractMethod(src, "private ReleaseResult doForceFreeByHold")).doesNotContain(".fire(");
        assertThat(extractMethod(src, "private ReleaseResult freeLockedHold")).doesNotContain(".fire(");
        assertThat(extractMethod(src, "private ReleaseResult doReleaseUnconsumed")).doesNotContain(".fire(");
        assertThat(extractMethod(src, "private ReleaseResult doReleaseAddOnHold")).doesNotContain(".fire(");
        assertThat(extractMethod(src, "private ConfirmPaidResult doConfirmPaidSlots")).doesNotContain(".fire(");
        assertThat(extractMethod(src, "public ReleaseResult releaseLockInOpenTx")).doesNotContain(".fire(");
        assertThat(extractMethod(src, "public ConfirmPaidResult confirmPaidSlotsInOpenTx")).doesNotContain(".fire(");
    }

    private static String extractMethod(String src, String signature) {
        int start = src.indexOf(signature);
        assertThat(start).as(signature).isGreaterThanOrEqualTo(0);
        int brace = src.indexOf('{', start);
        int depth = 0;
        int end = brace;
        for (int i = brace; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    end = i;
                    break;
                }
            }
        }
        return src.substring(brace, end);
    }

    private static Fixture fixture(String requestId) {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService occupy = OccupyFixtures.service(store);
        AppClock clock = new AppClock(Clock.fixed(
                LocalDate.of(2026, 8, 14).atTime(LocalTime.of(19, 0)).atZone(AppClock.SHANGHAI).toInstant(),
                AppClock.SHANGHAI));
        OrderStateMachine machine = new OrderStateMachine(store, occupy, clock);
        LockNewResult locked = occupy.lockNew(OccupyFixtures.cmd(requestId, OccupyFixtures.T1, OccupyFixtures.START_1930));
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
