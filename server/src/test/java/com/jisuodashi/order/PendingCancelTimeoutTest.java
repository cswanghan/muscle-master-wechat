package com.jisuodashi.order;

import com.jisuodashi.catalog.DemoCatalogIds;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.inventory.LockNewResult;
import com.jisuodashi.inventory.OccupyFixtures;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.inventory.SlotStatus;
import com.jisuodashi.job.JobRunner;
import com.jisuodashi.job.SlotGenerateJob;
import com.jisuodashi.job.SlotScanJob;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;

import static com.jisuodashi.inventory.OccupyFixtures.START_1930;
import static com.jisuodashi.inventory.OccupyFixtures.T1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PendingCancelTimeoutTest {

    @Test
    void cancelPendingPayClosesAndFreesSlots() {
        Fixture f = fixture("to-cancel");
        FireResult fired = f.machine.fire(f.locked.orderId(), OrderEvent.USER_CANCEL, FireContext.customer(OccupyFixtures.CUSTOMER));
        assertThat(fired.to()).isEqualTo(OrderStatus.CLOSED);
        assertThat(f.store.findOrderByHoldId(f.locked.holdId()).status()).isEqualTo("CLOSED");
        assertThat(f.store.occupancies).isEmpty();
        assertThat(f.store.therapistSlot(T1, LocalDate.of(2026, 8, 14), START_1930).status).isEqualTo(SlotStatus.FREE);
    }

    @Test
    void cancelBookedIs40904AndKeepsOccupancy() {
        Fixture f = fixture("to-booked-cancel");
        f.machine.fire(f.locked.orderId(), OrderEvent.PAY_SUCCESS);
        assertThatThrownBy(() -> f.machine.fire(
                f.locked.orderId(), OrderEvent.USER_CANCEL, FireContext.customer(OccupyFixtures.CUSTOMER)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.ILLEGAL_TRANSITION);
        assertThat(f.store.findOrderByHoldId(f.locked.holdId()).status()).isEqualTo("BOOKED");
        assertThat(f.store.occupancies).hasSize(10);
    }

    @Test
    void timeoutJobClosesUnpaidAndReleasesLock() {
        Fixture f = fixture("to-timeout-job");
        f.store.jobByHold(f.locked.holdId()).runAt = LocalDate.of(2026, 8, 14).atTime(18, 50);
        assertThat(f.runner.drainDueJobs()).isEqualTo(1);
        assertThat(f.store.findOrderByHoldId(f.locked.holdId()).status()).isEqualTo("CLOSED");
        assertThat(f.store.jobByHold(f.locked.holdId()).status).isEqualTo("DONE");
        assertThat(f.store.jobByHold(f.locked.holdId()).lastError).isNull();
        assertThat(f.store.occupancies).isEmpty();
        assertThat(f.store.therapistSlot(T1, LocalDate.of(2026, 8, 14), START_1930).status).isEqualTo(SlotStatus.FREE);
    }

    @Test
    void paidThenExpireJobIsDoneOrderStaysBooked() {
        Fixture f = fixture("to-paid-expire");
        f.machine.fire(f.locked.orderId(), OrderEvent.PAY_SUCCESS);
        assertThat(f.store.findOrderByHoldId(f.locked.holdId()).status()).isEqualTo("BOOKED");
        assertThat(f.store.jobByHold(f.locked.holdId()).status).isEqualTo("DONE");

        f.store.jobByHold(f.locked.holdId()).status = "PENDING";
        f.store.jobByHold(f.locked.holdId()).runAt = LocalDate.of(2026, 8, 14).atTime(18, 50);
        assertThat(f.runner.drainDueJobs()).isEqualTo(1);

        assertThat(f.store.jobByHold(f.locked.holdId()).status).isEqualTo("DONE");
        assertThat(f.store.jobByHold(f.locked.holdId()).lastError).isNull();
        assertThat(f.store.findOrderByHoldId(f.locked.holdId()).status()).isEqualTo("BOOKED");
        assertThat(f.store.occupancies).hasSize(10);
        assertThat(f.store.therapistSlot(T1, LocalDate.of(2026, 8, 14), START_1930).status).isEqualTo(SlotStatus.BOOKED);
        assertThat(f.store.therapistSlot(T1, LocalDate.of(2026, 8, 14), 82).status).isEqualTo(SlotStatus.BUFFER);
    }

    @Test
    void scanExpiredPendingPayFiresTimeoutAndCloses() {
        Fixture f = fixture("to-scan-fire");
        f.store.expireHold(f.locked.holdId(), LocalDate.of(2026, 8, 14).atTime(18, 50));
        var scan = new SlotScanJob(f.occupy, f.machine).run();
        assertThat(scan.pendingReleased()).isEqualTo(1);
        assertThat(f.store.findOrderByHoldId(f.locked.holdId()).status()).isEqualTo("CLOSED");
        assertThat(f.store.occupancies).isEmpty();
    }

    @Test
    void cancelClosedOwnerReplayIs200() {
        Fixture f = fixture("to-cancel-replay");
        BookingService bookings = new BookingService(f.occupy, f.machine);
        var req = new BookingDtos.CancelBookingRequest("req-cancel-replay", "changed-mind");
        BookingDtos.CancelBookingResponse first = bookings.cancel(
                OccupyFixtures.CUSTOMER, String.valueOf(f.locked.orderId()), req);
        BookingDtos.CancelBookingResponse replay = bookings.cancel(
                OccupyFixtures.CUSTOMER, String.valueOf(f.locked.orderId()), req);
        assertThat(first.status()).isEqualTo("CLOSED");
        assertThat(replay.status()).isEqualTo("CLOSED");
        assertThat(replay.orderId()).isEqualTo(first.orderId());
        assertThat(f.store.occupancies).isEmpty();
    }

    @Test
    void drainDueJobsIsolatesFailureAndRecordsLastError() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService occupy = OccupyFixtures.service(store);
        AppClock clock = new AppClock(Clock.fixed(
                LocalDate.of(2026, 8, 14).atTime(LocalTime.of(19, 0)).atZone(AppClock.SHANGHAI).toInstant(),
                AppClock.SHANGHAI));
        LockNewResult boom = occupy.lockNew(OccupyFixtures.cmd("drain-boom", T1, START_1930));
        LockNewResult ok = occupy.lockNew(OccupyFixtures.cmd("drain-ok", DemoCatalogIds.THERAPIST_CHEN, START_1930));
        store.jobByHold(boom.holdId()).runAt = LocalDate.of(2026, 8, 14).atTime(18, 50);
        store.jobByHold(ok.holdId()).runAt = LocalDate.of(2026, 8, 14).atTime(18, 50);

        OrderStateMachine machine = new OrderStateMachine(store, occupy, clock) {
            @Override
            public FireResult fire(long orderId, OrderEvent event, FireContext ctx) {
                if (orderId == boom.orderId()) {
                    throw new IllegalStateException("boom");
                }
                return super.fire(orderId, event, ctx);
            }
        };
        JobRunner runner = new JobRunner(
                new SlotGenerateJob(null),
                new SlotScanJob(occupy, machine),
                store,
                clock,
                "w-test",
                null,
                machine);

        assertThat(runner.drainDueJobs()).isEqualTo(2);
        assertThat(store.jobByHold(boom.holdId()).status).isEqualTo("FAILED");
        assertThat(store.jobByHold(boom.holdId()).lastError).isEqualTo("boom");
        assertThat(store.findOrderByHoldId(boom.holdId()).status()).isEqualTo("PENDING_PAY");
        assertThat(store.jobByHold(ok.holdId()).status).isEqualTo("DONE");
        assertThat(store.findOrderByHoldId(ok.holdId()).status()).isEqualTo("CLOSED");
        assertThat(store.occupancies).hasSize(10);
    }

    @Test
    void jobRunnerDispatchFiresAndDoesNotCallReleaseLockFirst() throws Exception {
        Path file = Path.of("src/main/java/com/jisuodashi/job/JobRunner.java");
        if (!Files.isRegularFile(file)) {
            file = Path.of("server/src/main/java/com/jisuodashi/job/JobRunner.java");
        }
        String src = Files.readString(file);
        assertThat(src).contains("OrderEvent.PAY_TIMEOUT");
        assertThat(src).contains("machine.fire(");
        assertThat(src).doesNotContain("releaseLock(");
        assertThat(src).doesNotContain("releaseLockInOpenTx");
        assertThat(JobRunner.parseOrderId("{\"orderId\":42,\"holdId\":9}")).isEqualTo(42L);
        assertThat(JobRunner.parseHoldId("hold:99")).isEqualTo(99L);
    }

    private static Fixture fixture(String requestId) {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService occupy = OccupyFixtures.service(store);
        AppClock clock = new AppClock(Clock.fixed(
                LocalDate.of(2026, 8, 14).atTime(LocalTime.of(19, 0)).atZone(AppClock.SHANGHAI).toInstant(),
                AppClock.SHANGHAI));
        OrderStateMachine machine = new OrderStateMachine(store, occupy, clock);
        LockNewResult locked = occupy.lockNew(OccupyFixtures.cmd(requestId, T1, START_1930));
        JobRunner runner = new JobRunner(
                new SlotGenerateJob(null),
                new SlotScanJob(occupy, machine),
                store,
                clock,
                "w-test",
                null,
                machine);
        return new Fixture(store, occupy, machine, locked, runner);
    }

    private record Fixture(
            InMemorySlotOccupyStore store,
            SlotOccupyService occupy,
            OrderStateMachine machine,
            LockNewResult locked,
            JobRunner runner
    ) {
    }
}
