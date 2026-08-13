package com.jisuodashi.inventory;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.inventory.InMemorySlotOccupyStore.MutableSlot;
import com.jisuodashi.job.ForceReleaseJob;
import com.jisuodashi.job.JobRunner;
import com.jisuodashi.job.SlotGenerateJob;
import com.jisuodashi.job.SlotScanJob;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static com.jisuodashi.inventory.OccupyFixtures.BED1;
import static com.jisuodashi.inventory.OccupyFixtures.START_1930;
import static com.jisuodashi.inventory.OccupyFixtures.T1;
import static com.jisuodashi.inventory.OccupyFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlotReleaseServiceTest {

    @Test
    void releaseLockFreesPendingPayLockedSlotsAndIsIdempotent() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        LockNewResult locked = service.lockNew(OccupyFixtures.cmd("rel-timeout", T1, START_1930));

        ReleaseResult first = service.releaseLock(locked.holdId());
        assertThat(first.outcome()).isEqualTo(ReleaseResult.FREED);
        assertThat(first.occupancyDeleted()).isEqualTo(10);
        assertThat(first.therapistFreed()).isEqualTo(5);
        assertThat(first.bedFreed()).isEqualTo(5);
        assertSlotsFree(store, locked.holdId(), locked.orderId());
        assertThat(store.occupancies).isEmpty();
        assertThat(store.findOrderByHoldId(locked.holdId()).status())
                .isEqualTo(SlotOccupyService.ORDER_PENDING_PAY);

        ReleaseResult second = service.releaseLock(locked.holdId());
        assertThat(second.outcome()).isEqualTo(ReleaseResult.IDEMPOTENT);
        assertThat(second.occupancyDeleted()).isZero();
        assertThat(store.occupancies).isEmpty();
        assertSlotsFree(store, locked.holdId(), locked.orderId());
    }

    @Test
    void releaseLockFreesClosedOrderLockedSlots() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        LockNewResult locked = service.lockNew(OccupyFixtures.cmd("rel-closed", T1, START_1930));
        store.setOrderStatus(locked.orderId(), SlotOccupyService.ORDER_CLOSED);

        ReleaseResult freed = service.releaseLock(locked.holdId());
        assertThat(freed.outcome()).isEqualTo(ReleaseResult.FREED);
        assertThat(freed.occupancyDeleted()).isEqualTo(10);
        assertSlotsFree(store, locked.holdId(), locked.orderId());
        assertThat(store.findOrderByHoldId(locked.holdId()).status())
                .isEqualTo(SlotOccupyService.ORDER_CLOSED);
    }

    @Test
    void releaseLockDoesNotFreePaidOrder() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        LockNewResult locked = service.lockNew(OccupyFixtures.cmd("rel-paid", T1, START_1930));
        ConfirmPaidResult paid = service.confirmPaidSlots(locked.orderId());

        assertThat(paid.therapistUpdated()).isEqualTo(5);
        assertThat(paid.bedUpdated()).isEqualTo(5);
        assertThat(paid.jobMarkedDone()).isEqualTo(1);
        assertThat(store.jobByHold(locked.holdId()).status).isEqualTo("DONE");
        assertBookedBuffer(store, locked.orderId());

        store.setOrderStatus(locked.orderId(), "BOOKED");
        ReleaseResult skipped = service.releaseLock(locked.holdId());
        assertThat(skipped.skipped()).isTrue();
        assertThat(skipped.outcome()).isEqualTo(ReleaseResult.SKIPPED_NOT_PENDING);
        assertThat(store.occupancies).hasSize(10);
        assertBookedBuffer(store, locked.orderId());
    }

    @Test
    void confirmPaidThenExpireJobDoesNotRelease() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        LockNewResult locked = service.lockNew(OccupyFixtures.cmd("rel-expire-paid", T1, START_1930));
        service.confirmPaidSlots(locked.orderId());
        store.setOrderStatus(locked.orderId(), "BOOKED");
        store.jobByHold(locked.holdId()).status = "PENDING";
        store.jobByHold(locked.holdId()).runAt = TODAY.atTime(18, 50);

        SlotScanResult scan = service.scanExpiredLocks();
        assertThat(scan.holdsSeen()).isZero();

        JobRunner runner = runner(store, service);
        assertThat(runner.drainDueJobs()).isEqualTo(1);
        assertThat(store.jobByHold(locked.holdId()).status).isEqualTo("DONE");
        assertThat(store.occupancies).hasSize(10);
        assertBookedBuffer(store, locked.orderId());
    }

    @Test
    void releaseLockTreatsMissingOrderAsOrphan() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        long orphanHold = 7_700_000_000_000_000_001L;
        plantLockedBed(store, BED1, orphanHold, 88L);

        ReleaseResult r = service.releaseLock(orphanHold);
        assertThat(r.outcome()).isEqualTo(ReleaseResult.ORPHAN_FREED);
        assertThat(r.bedFreed()).isEqualTo(5);
        assertThat(store.occupancies).isEmpty();
        for (int slot = 78; slot <= 82; slot++) {
            assertThat(store.bedSlot(BED1, TODAY, slot).status).isEqualTo(SlotStatus.FREE);
            assertThat(store.bedSlot(BED1, TODAY, slot).holdId).isNull();
        }
    }

    @Test
    void forceFreeByHoldReleasesNonPendingLockedOrphan() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        LockNewResult locked = service.lockNew(OccupyFixtures.cmd("rel-force", T1, START_1930));
        store.setOrderStatus(locked.orderId(), "BOOKED");

        assertThat(service.releaseLock(locked.holdId()).skipped()).isTrue();
        ReleaseResult forced = new ForceReleaseJob(service).run(locked.holdId());
        assertThat(forced.outcome()).isEqualTo(ReleaseResult.ORPHAN_FREED);
        assertThat(store.occupancies).isEmpty();
        assertSlotsFree(store, locked.holdId(), locked.orderId());
    }

    @Test
    void forceFreeByHoldDoesNotDeleteBookedOccupancy() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        LockNewResult locked = service.lockNew(OccupyFixtures.cmd("rel-force-paid", T1, START_1930));
        service.confirmPaidSlots(locked.orderId());
        store.setOrderStatus(locked.orderId(), "BOOKED");

        ReleaseResult forced = service.forceFreeByHold(locked.holdId());
        assertThat(forced.outcome()).isEqualTo(ReleaseResult.IDEMPOTENT);
        assertThat(forced.occupancyDeleted()).isZero();
        assertThat(store.occupancies).hasSize(10);
        assertBookedBuffer(store, locked.orderId());
    }

    @Test
    void confirmPaidSlotsMissingOrderIs404() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        assertThatThrownBy(() -> OccupyFixtures.service(store).confirmPaidSlots(1L))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.NOT_FOUND);
    }

    @Test
    void jobRunnerTreats40904AsDone() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        LockNewResult locked = service.lockNew(OccupyFixtures.cmd("rel-40904", T1, START_1930));
        var job = store.jobByHold(locked.holdId());
        job.status = "RUNNING";
        job.leaseUntil = TODAY.atTime(18, 50);
        JobRunner runner = runner(store, service);
        runner.completeJob(store.findJob(job.id), ErrorCodes.ILLEGAL_TRANSITION, "should not fail");
        assertThat(store.job(job.id).status).isEqualTo("DONE");
        assertThat(store.job(job.id).lastError).isNull();
        runner.completeJob(store.findJob(job.id), ErrorCodes.INTERNAL, "boom");
        assertThat(store.job(job.id).status).isEqualTo("FAILED");
        assertThat(store.job(job.id).lastError).isEqualTo("boom");
    }

    @Test
    void jobRunnerClaimsPendingOrExpiredLease() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        LockNewResult a = service.lockNew(OccupyFixtures.cmd("rel-claim-a", T1, START_1930));
        store.jobByHold(a.holdId()).runAt = TODAY.atTime(18, 50);

        JobRunner runner = runner(store, service);
        assertThat(runner.drainDueJobs()).isEqualTo(1);
        assertThat(store.jobByHold(a.holdId()).status).isEqualTo("DONE");
        assertThat(store.occupancies).hasSize(10);

        LockNewResult b = OccupyFixtures.service(store).lockNew(
                OccupyFixtures.cmd("rel-claim-b", OccupyFixtures.T2, START_1930));
        var running = store.jobByHold(b.holdId());
        running.status = "RUNNING";
        running.leaseUntil = TODAY.atTime(18, 59);
        running.retryCount = 0;
        assertThat(runner.drainDueJobs()).isEqualTo(1);
        assertThat(store.jobByHold(b.holdId()).status).isEqualTo("DONE");
        assertThat(store.jobByHold(b.holdId()).retryCount).isEqualTo(1);
    }

    private static void plantLockedBed(InMemorySlotOccupyStore store, long bedId, long holdId, long orderId) {
        LocalDateTime expire = TODAY.atTime(18, 50);
        store.beginWork();
        for (int slot = 78; slot <= 82; slot++) {
            MutableSlot row = store.bedSlot(bedId, TODAY, slot);
            row.status = SlotStatus.LOCKED;
            row.holdId = holdId;
            row.orderId = orderId;
            row.lockExpireAt = expire;
            store.insertOccupancy(new SlotOccupyStore.OccupancyInsert(
                    8_000_000_000_000_000_000L + slot, ResourceType.BED, bedId, TODAY, slot,
                    orderId, holdId, TODAY.atTime(18, 0)));
        }
        store.commitWork();
    }

    private static void assertSlotsFree(InMemorySlotOccupyStore store, long holdId, long orderId) {
        for (int slot = 78; slot <= 82; slot++) {
            MutableSlot t = store.therapistSlot(T1, TODAY, slot);
            if (t != null && (holdId == (t.holdId == null ? -1 : t.holdId)
                    || orderId == (t.orderId == null ? -1 : t.orderId)
                    || SlotStatus.FREE.equals(t.status))) {
                assertThat(t.status).isEqualTo(SlotStatus.FREE);
                assertThat(t.holdId).isNull();
                assertThat(t.orderId).isNull();
                assertThat(t.lockExpireAt).isNull();
            }
            MutableSlot b = store.bedSlot(BED1, TODAY, slot);
            assertThat(b.status).isEqualTo(SlotStatus.FREE);
            assertThat(b.holdId).isNull();
            assertThat(b.lockExpireAt).isNull();
        }
    }

    private static void assertBookedBuffer(InMemorySlotOccupyStore store, long orderId) {
        for (int slot = 78; slot <= 81; slot++) {
            assertThat(store.therapistSlot(T1, TODAY, slot).status).isEqualTo(SlotStatus.BOOKED);
            assertThat(store.bedSlot(BED1, TODAY, slot).status).isEqualTo(SlotStatus.BOOKED);
            assertThat(store.therapistSlot(T1, TODAY, slot).lockExpireAt).isNull();
            assertThat(store.bedSlot(BED1, TODAY, slot).lockExpireAt).isNull();
            assertThat(store.therapistSlot(T1, TODAY, slot).orderId).isEqualTo(orderId);
        }
        assertThat(store.therapistSlot(T1, TODAY, 82).status).isEqualTo(SlotStatus.BUFFER);
        assertThat(store.bedSlot(BED1, TODAY, 82).status).isEqualTo(SlotStatus.BUFFER);
        assertThat(store.therapistSlot(T1, TODAY, 82).lockExpireAt).isNull();
    }

    private static JobRunner runner(InMemorySlotOccupyStore store, SlotOccupyService service) {
        return new JobRunner(
                new SlotGenerateJob(null),
                new SlotScanJob(service),
                store,
                new com.jisuodashi.common.AppClock(java.time.Clock.fixed(
                        TODAY.atTime(19, 0).atZone(com.jisuodashi.common.AppClock.SHANGHAI).toInstant(),
                        com.jisuodashi.common.AppClock.SHANGHAI)),
                "w-test",
                null);
    }
}
