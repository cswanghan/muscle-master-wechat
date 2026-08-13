package com.jisuodashi.inventory;

import com.jisuodashi.inventory.InMemorySlotOccupyStore.MutableSlot;
import com.jisuodashi.job.SlotScanJob;
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

        SlotScanResult scan = new SlotScanJob(service).run();
        assertThat(scan.holdIds()).containsExactly(locked.holdId());
        assertThat(scan.pendingReleased()).isEqualTo(1);
        assertThat(scan.orphansFreed()).isZero();
        assertThat(store.occupancies).isEmpty();
        assertThat(store.therapistSlot(T1, TODAY, 78).status).isEqualTo(SlotStatus.FREE);
        assertThat(store.bedSlot(BED1, TODAY, 78).status).isEqualTo(SlotStatus.FREE);
        assertThat(store.findOrderByHoldId(locked.holdId()).status())
                .isEqualTo(SlotOccupyService.ORDER_PENDING_PAY);
    }

    @Test
    void paidOrderNotReleasedByExpireScan() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        LockNewResult locked = service.lockNew(OccupyFixtures.cmd("scan-paid", T1, START_1930));
        service.confirmPaidSlots(locked.orderId());
        store.setOrderStatus(locked.orderId(), "BOOKED");
        store.expireHold(locked.holdId(), TODAY.atTime(18, 50));

        SlotScanResult scan = new SlotScanJob(service).run();
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

        SlotScanResult scan = new SlotScanJob(service).run();
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

        SlotScanResult scan = new SlotScanJob(service).run();
        assertThat(scan.holdIds()).containsExactly(therapistHold, bedHold);
        assertThat(scan.orphansFreed()).isEqualTo(2);
        assertThat(store.occupancies).isEmpty();
        assertThat(store.therapistSlot(T2, TODAY, 78).status).isEqualTo(SlotStatus.FREE);
        assertThat(store.bedSlot(BED2, TODAY, 78).status).isEqualTo(SlotStatus.FREE);
    }

    @Test
    void addOnHoldIsNotReleasedWithoutFire() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        LockNewResult locked = service.lockNew(OccupyFixtures.cmd("scan-addon", T1, START_1930));
        long addOn = 6_600_000_000_000_000_099L;
        store.setAddOnHoldId(locked.orderId(), addOn);
        store.setOrderStatus(locked.orderId(), "IN_SERVICE");
        plantBedOnly(store, BED2, addOn, TODAY.atTime(18, 30));

        SlotScanResult scan = new SlotScanJob(service).run();
        assertThat(scan.holdIds()).contains(addOn);
        assertThat(scan.addonSkipped()).isEqualTo(1);
        assertThat(scan.orphansFreed()).isZero();
        assertThat(store.bedSlot(BED2, TODAY, 78).status).isEqualTo(SlotStatus.LOCKED);
    }

    @Test
    void stalePaidLockedIsNotFreed() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        LockNewResult locked = service.lockNew(OccupyFixtures.cmd("scan-stale", T1, START_1930));
        store.setOrderStatus(locked.orderId(), "BOOKED");
        store.expireHold(locked.holdId(), TODAY.atTime(18, 50));

        SlotScanResult scan = new SlotScanJob(service).run();
        assertThat(scan.stalePaid()).isEqualTo(1);
        assertThat(scan.pendingReleased()).isZero();
        assertThat(store.occupancies).hasSize(10);
        assertThat(store.therapistSlot(T1, TODAY, 78).status).isEqualTo(SlotStatus.LOCKED);
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
