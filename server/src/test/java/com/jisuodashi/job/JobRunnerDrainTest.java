package com.jisuodashi.job;

import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.inventory.DelayedJobStore.DelayedJobRow;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.inventory.LockNewResult;
import com.jisuodashi.inventory.OccupyFixtures;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.inventory.SlotOccupyStore.DelayedJobInsert;
import com.jisuodashi.order.FireContext;
import com.jisuodashi.order.FireResult;
import com.jisuodashi.order.OrderEvent;
import com.jisuodashi.order.OrderStateMachine;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class JobRunnerDrainTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 14);

    @Test
    void missingOrderIdIsFailedNotDone() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        AppClock clock = clock();
        insertJob(store, 11L, "RELEASE_LOCK", "orphan:11", "{}", clock.now());
        JobRunner runner = new JobRunner(null, null, store, clock, "t", null, machine(store));

        int code = runner.runClaimedJob(store.findJob(11L));
        DelayedJobRow done = store.findJob(11L);
        assertThat(code).isEqualTo(ErrorCodes.INTERNAL);
        assertThat(done.status()).isEqualTo("FAILED");
        assertThat(done.lastError()).contains("code=" + ErrorCodes.INTERNAL);
    }

    @Test
    void holdBizKeyResolvesOrderId() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService occupy = OccupyFixtures.service(store);
        AppClock clock = clock();
        LockNewResult locked = occupy.lockNew(
                OccupyFixtures.cmd("drain-hold", OccupyFixtures.T1, OccupyFixtures.START_1930));
        insertJob(store, 12L, SlotOccupyService.JOB_RELEASE_LOCK,
                "hold:" + locked.holdId(), "{}", clock.now().minusMinutes(1));
        JobRunner runner = new JobRunner(null, null, store, clock, "t", null, machine(store, occupy, clock));

        assertThat(runner.resolveOrderId(store.findJob(12L))).isEqualTo(locked.orderId());
        int code = runner.dispatch(store.findJob(12L));
        assertThat(code).isEqualTo(ErrorCodes.OK);
        assertThat(store.findOrderById(locked.orderId()).status()).isEqualTo("CLOSED");
    }

    @Test
    void drainContinuesAfterRuntimeExceptionAndRecordsLastError() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService occupy = OccupyFixtures.service(store);
        AppClock clock = clock();
        LockNewResult locked = occupy.lockNew(
                OccupyFixtures.cmd("drain-ok", OccupyFixtures.T1, OccupyFixtures.START_1930));
        insertJob(store, 21L, SlotOccupyService.JOB_RELEASE_LOCK, "hold:21",
                "{\"orderId\":21}", clock.now().minusMinutes(1));
        insertJob(store, 22L, SlotOccupyService.JOB_RELEASE_LOCK, "hold:" + locked.holdId(),
                "{\"orderId\":" + locked.orderId() + "}", clock.now().minusMinutes(1));

        OrderStateMachine boom = new OrderStateMachine(store, occupy, clock) {
            @Override
            public FireResult fire(long orderId, OrderEvent event, FireContext ctx) {
                if (orderId == 21L) {
                    throw new IllegalStateException("boom-job-21");
                }
                return super.fire(orderId, event, ctx);
            }
        };
        JobRunner runner = new JobRunner(null, null, store, clock, "t", null, boom);
        int n = runner.drainDueJobs();
        assertThat(n).isGreaterThanOrEqualTo(2);
        assertThat(store.findJob(21L).status()).isEqualTo("FAILED");
        assertThat(store.findJob(21L).lastError()).isEqualTo("boom-job-21");
        assertThat(store.findJob(22L).status()).isEqualTo("DONE");
        assertThat(store.findOrderById(locked.orderId()).status()).isEqualTo("CLOSED");
    }

    private static void insertJob(
            InMemorySlotOccupyStore store, long id, String type, String biz, String payload, LocalDateTime runAt) {
        store.beginWork();
        store.insertDelayedJob(new DelayedJobInsert(id, type, biz, payload, runAt, "PENDING", runAt));
        store.commitWork();
    }

    private static OrderStateMachine machine(InMemorySlotOccupyStore store) {
        return machine(store, OccupyFixtures.service(store), clock());
    }

    private static OrderStateMachine machine(
            InMemorySlotOccupyStore store, SlotOccupyService occupy, AppClock clock) {
        return new OrderStateMachine(store, occupy, clock);
    }

    private static AppClock clock() {
        return new AppClock(Clock.fixed(
                TODAY.atTime(LocalTime.of(19, 0)).atZone(AppClock.SHANGHAI).toInstant(),
                AppClock.SHANGHAI));
    }
}
