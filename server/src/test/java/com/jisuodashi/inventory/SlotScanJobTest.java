package com.jisuodashi.inventory;

import com.jisuodashi.inventory.InMemorySlotOccupyStore.MutableSlot;
import com.jisuodashi.job.SlotScanJob;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static com.jisuodashi.inventory.OccupyFixtures.BED1;
import static com.jisuodashi.inventory.OccupyFixtures.BED2;
import static com.jisuodashi.inventory.OccupyFixtures.START_1930;
import static com.jisuodashi.inventory.OccupyFixtures.T1;
import static com.jisuodashi.inventory.OccupyFixtures.T2;
import static com.jisuodashi.inventory.OccupyFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;

class SlotScanJobTest {

    @Test
    void timeoutPendingPayIsReleasedByScan() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        LockNewResult locked = service.lockNew(OccupyFixtures.cmd("scan-timeout", T1, START_1930));
        store.expireHold(locked.holdId(), TODAY.atTime(18, 50));

        SlotScanResult scan = scanJob(store, service).run();
        assertThat(scan.holdIds()).containsExactly(locked.holdId());
        assertThat(scan.pendingReleased()).isEqualTo(1);
        assertThat(scan.orphansFreed()).isZero();
        assertThat(store.occupancies).isEmpty();
        assertThat(store.therapistSlot(T1, TODAY, 78).status).isEqualTo(SlotStatus.FREE);
        assertThat(store.bedSlot(BED1, TODAY, 78).status).isEqualTo(SlotStatus.FREE);
        assertThat(store.findOrderByHoldId(locked.holdId()).status())
                .isEqualTo(SlotOccupyService.ORDER_CLOSED);
    }

    @Test
    void closedOrderExpiredLockIsReleased() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        LockNewResult locked = service.lockNew(OccupyFixtures.cmd("scan-closed", T1, START_1930));
        store.setOrderStatus(locked.orderId(), SlotOccupyService.ORDER_CLOSED);
        store.expireHold(locked.holdId(), TODAY.atTime(18, 50));

        SlotScanResult scan = scanJob(store, service).run();
        assertThat(scan.pendingReleased()).isEqualTo(1);
        assertThat(store.occupancies).isEmpty();
        assertThat(store.therapistSlot(T1, TODAY, 78).status).isEqualTo(SlotStatus.FREE);
        assertThat(store.findOrderByHoldId(locked.holdId()).status())
                .isEqualTo(SlotOccupyService.ORDER_CLOSED);
    }

    @Test
    void paidOrderNotReleasedByExpireScan() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        LockNewResult locked = service.lockNew(OccupyFixtures.cmd("scan-paid", T1, START_1930));
        service.confirmPaidSlots(locked.orderId());
        store.setOrderStatus(locked.orderId(), "BOOKED");
        store.expireHold(locked.holdId(), TODAY.atTime(18, 50));

        SlotScanResult scan = scanJob(store, service).run();
        assertThat(scan.holdsSeen()).isZero();
        assertThat(store.occupancies).hasSize(10);
        assertThat(store.therapistSlot(T1, TODAY, 78).status).isEqualTo(SlotStatus.BOOKED);
        assertThat(store.therapistSlot(T1, TODAY, 82).status).isEqualTo(SlotStatus.BUFFER);
        assertThat(store.jobByHold(locked.holdId()).status).isEqualTo("DONE");
    }

    @Test
    void bedOnlyOrphanIsReleased() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        long orphanHold = 6_600_000_000_000_000_002L;
        plantBedOnly(store, BED2, orphanHold, TODAY.atTime(18, 45));

        SlotScanResult scan = scanJob(store, service).run();
        assertThat(scan.holdIds()).containsExactly(orphanHold);
        assertThat(scan.orphansFreed()).isEqualTo(1);
        assertThat(store.occupancies).isEmpty();
        for (int slot = 78; slot <= 82; slot++) {
            assertThat(store.bedSlot(BED2, TODAY, slot).status).isEqualTo(SlotStatus.FREE);
            assertThat(store.bedSlot(BED2, TODAY, slot).holdId).isNull();
        }
        assertThat(store.therapistSlot(T1, TODAY, 78).status).isEqualTo(SlotStatus.FREE);
    }

    @Test
    void dualTableScanFindsTherapistAndBedHolds() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        long therapistHold = 6_600_000_000_000_000_011L;
        long bedHold = 6_600_000_000_000_000_012L;
        plantTherapistOnly(store, T2, therapistHold, TODAY.atTime(18, 40));
        plantBedOnly(store, BED2, bedHold, TODAY.atTime(18, 41));

        SlotScanResult scan = scanJob(store, service).run();
        assertThat(scan.holdIds()).containsExactly(therapistHold, bedHold);
        assertThat(scan.orphansFreed()).isEqualTo(2);
        assertThat(store.occupancies).isEmpty();
        assertThat(store.therapistSlot(T2, TODAY, 78).status).isEqualTo(SlotStatus.FREE);
        assertThat(store.bedSlot(BED2, TODAY, 78).status).isEqualTo(SlotStatus.FREE);
    }

    @Test
    void expiredAddOnHoldFiresTimeoutAndRestoresBuffer() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        LockNewResult locked = service.lockNew(OccupyFixtures.cmd("scan-addon", T1, START_1930));
        service.confirmPaidSlots(locked.orderId());
        store.setOrderStatus(locked.orderId(), "IN_SERVICE");
        ExtendOwnResult ext = service.extendOwn(locked.orderId(), OccupyFixtures.P60, 2, false);
        store.expireHold(ext.addHoldId(), TODAY.atTime(18, 30));

        SlotScanResult scan = scanJob(store, service).run();
        assertThat(scan.holdIds()).contains(ext.addHoldId());
        assertThat(scan.addonSkipped()).isEqualTo(1);
        assertThat(scan.orphansFreed()).isZero();
        assertThat(store.findOrderById(locked.orderId()).addOnHoldId()).isNull();
        assertThat(store.findOrderById(locked.orderId()).status()).isEqualTo("IN_SERVICE");
        assertThat(store.therapistSlot(T1, TODAY, 82).status).isEqualTo(SlotStatus.BUFFER);
        assertThat(store.therapistSlot(T1, TODAY, 83).status).isEqualTo(SlotStatus.FREE);
        assertThat(store.bedSlot(BED1, TODAY, 84).status).isEqualTo(SlotStatus.FREE);
    }

    @Test
    void scanExpiredLocksReleasesAddOnHoldWithoutFire() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        LockNewResult locked = service.lockNew(OccupyFixtures.cmd("scan-addon-nf", T1, START_1930));
        service.confirmPaidSlots(locked.orderId());
        store.setOrderStatus(locked.orderId(), "IN_SERVICE");
        ExtendOwnResult ext = service.extendOwn(locked.orderId(), OccupyFixtures.P60, 2, false);
        store.expireHold(ext.addHoldId(), TODAY.atTime(18, 30));

        SlotScanResult scan = service.scanExpiredLocks();
        assertThat(scan.addonSkipped()).isEqualTo(1);
        assertThat(store.findOrderById(locked.orderId()).addOnHoldId()).isNull();
        assertThat(store.therapistSlot(T1, TODAY, 82).status).isEqualTo(SlotStatus.BUFFER);
        assertThat(store.therapistSlot(T1, TODAY, 83).status).isEqualTo(SlotStatus.FREE);
    }

    @Test
    void stalePaidLockedIsNotFreed() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        LockNewResult locked = service.lockNew(OccupyFixtures.cmd("scan-stale", T1, START_1930));
        store.setOrderStatus(locked.orderId(), "BOOKED");
        store.expireHold(locked.holdId(), TODAY.atTime(18, 50));

        SlotScanResult scan = scanJob(store, service).run();
        assertThat(scan.stalePaid()).isEqualTo(1);
        assertThat(scan.pendingReleased()).isZero();
        assertThat(store.occupancies).hasSize(10);
        assertThat(store.therapistSlot(T1, TODAY, 78).status).isEqualTo(SlotStatus.LOCKED);
    }

    @Test
    void scanIncrementsStalePaidMetric() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        SlotOccupyService service = new SlotOccupyService(
                store, new InMemoryTherapistDayLock(),
                new java.util.concurrent.atomic.AtomicLong(9_200_000_000_000_000_000L)::incrementAndGet,
                new com.jisuodashi.common.AppClock(java.time.Clock.fixed(
                        TODAY.atTime(19, 0).atZone(com.jisuodashi.common.AppClock.SHANGHAI).toInstant(),
                        com.jisuodashi.common.AppClock.SHANGHAI)),
                meters);
        LockNewResult locked = service.lockNew(OccupyFixtures.cmd("scan-metric", T1, START_1930));
        store.setOrderStatus(locked.orderId(), "BOOKED");
        store.expireHold(locked.holdId(), TODAY.atTime(18, 20));

        SlotScanResult scan = scanJob(store, service).run();
        assertThat(scan.stalePaid()).isEqualTo(1);
        assertThat(meters.counter("slot.locked.stale_paid").count()).isEqualTo(1.0);
        // slot.locked.stale 现在由 BusinessMetrics 以 60s 节流刮取，这里只校验刮取源。
        assertThat(store.countLockedExpiredBefore(TODAY.atTime(18, 50))).isEqualTo(10);
    }

    private static SlotScanJob scanJob(InMemorySlotOccupyStore store, SlotOccupyService service) {
        com.jisuodashi.common.AppClock clock = new com.jisuodashi.common.AppClock(java.time.Clock.fixed(
                TODAY.atTime(19, 0).atZone(com.jisuodashi.common.AppClock.SHANGHAI).toInstant(),
                com.jisuodashi.common.AppClock.SHANGHAI));
        return new SlotScanJob(service, new com.jisuodashi.order.OrderStateMachine(store, service, clock));
    }

    private static void plantBedOnly(InMemorySlotOccupyStore store, long bedId, long holdId, LocalDateTime expire) {
        store.beginWork();
        for (int slot = 78; slot <= 82; slot++) {
            MutableSlot row = store.bedSlot(bedId, TODAY, slot);
            row.status = SlotStatus.LOCKED;
            row.holdId = holdId;
            row.lockExpireAt = expire;
            store.insertOccupancy(new SlotOccupyStore.OccupancyInsert(
                    holdId + slot, ResourceType.BED, bedId, TODAY, slot, holdId, holdId, expire));
        }
        store.commitWork();
    }

    private static void plantTherapistOnly(
            InMemorySlotOccupyStore store, long therapistId, long holdId, LocalDateTime expire) {
        store.beginWork();
        for (int slot = 78; slot <= 82; slot++) {
            MutableSlot row = store.therapistSlot(therapistId, TODAY, slot);
            row.status = SlotStatus.LOCKED;
            row.holdId = holdId;
            row.lockExpireAt = expire;
            store.insertOccupancy(new SlotOccupyStore.OccupancyInsert(
                    holdId + slot, ResourceType.THERAPIST, therapistId, TODAY, slot, holdId, holdId, expire));
        }
        store.commitWork();
    }
}
