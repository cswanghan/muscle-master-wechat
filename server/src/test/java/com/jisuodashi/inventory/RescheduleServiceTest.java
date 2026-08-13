package com.jisuodashi.inventory;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.inventory.InMemorySlotOccupyStore.MutableSlot;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static com.jisuodashi.inventory.OccupyFixtures.BED1;
import static com.jisuodashi.inventory.OccupyFixtures.BED2;
import static com.jisuodashi.inventory.OccupyFixtures.START_1930;
import static com.jisuodashi.inventory.OccupyFixtures.T1;
import static com.jisuodashi.inventory.OccupyFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RescheduleServiceTest {

    static final long T2 = OccupyFixtures.T2;
    static final int START_1600 = 64;
    static final int START_2000 = 80;

    @Test
    void moveStartSameTherapistAcquiresReleasesKeepsHold() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        long orderId = bookPaid(store, service, "rs-shift", T1, START_1930);
        long oldHold = store.findOrderById(orderId).holdId();
        int occBefore = store.occupancyCount();

        RescheduleResult moved = service.reschedule(cmd("rs-shift-1", orderId, TODAY, START_2000, T1));

        assertThat(moved.replay()).isFalse();
        assertThat(moved.therapistId()).isEqualTo(T1);
        assertThat(moved.startSlotNo()).isEqualTo(START_2000);
        assertThat(moved.endSlotNo()).isEqualTo(85);
        assertThat(moved.holdId()).isNotEqualTo(oldHold);
        assertThat(moved.acquireCount()).isEqualTo(4);
        assertThat(moved.releaseCount()).isEqualTo(4);
        assertThat(moved.keepCount()).isEqualTo(6);
        assertThat(store.occupancyCount()).isEqualTo(occBefore);
        assertThat(store.occupancies.values()).allMatch(o -> o.holdId() == moved.holdId());
        assertThat(store.occupancies.keySet()).doesNotHaveDuplicates();
        assertThat(store.delayedJobs).hasSize(1);

        assertPaidWindow(store, T1, moved.bedId(), TODAY, START_2000, orderId, moved.holdId());
        assertSlot(store.therapistSlot(T1, TODAY, 78), SlotStatus.FREE, null, null);
        assertSlot(store.therapistSlot(T1, TODAY, 79), SlotStatus.FREE, null, null);
        assertSlot(store.bedSlot(moved.bedId(), TODAY, 78), SlotStatus.FREE, null, null);
        assertSlot(store.bedSlot(moved.bedId(), TODAY, 79), SlotStatus.FREE, null, null);
        assertThat(store.changeLogs).hasSize(1);
        assertThat(store.changeLogs.getFirst().changeType()).isEqualTo(SlotOccupyService.CHANGE_RESCHEDULE);

        store.setOrderStatus(orderId, "CHECKED_IN");
        RescheduleResult replay = service.reschedule(cmd("rs-shift-1", orderId, TODAY, START_2000, T1));
        assertThat(replay.replay()).isTrue();
        assertThat(replay.holdId()).isEqualTo(moved.holdId());
        assertThat(store.occupancyCount()).isEqualTo(occBefore);
    }

    @Test
    void shiftEarlierRemapsKeepTailToBuffer() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        long orderId = bookPaid(store, service, "rs-back", T1, START_2000);

        RescheduleResult moved = service.reschedule(cmd("rs-back-1", orderId, TODAY, START_1930, T1));

        assertThat(moved.startSlotNo()).isEqualTo(START_1930);
        assertThat(moved.acquireCount()).isEqualTo(4);
        assertThat(moved.releaseCount()).isEqualTo(4);
        assertThat(moved.keepCount()).isEqualTo(6);
        assertPaidWindow(store, T1, moved.bedId(), TODAY, START_1930, orderId, moved.holdId());
        assertSlot(store.therapistSlot(T1, TODAY, 83), SlotStatus.FREE, null, null);
        assertSlot(store.therapistSlot(T1, TODAY, 84), SlotStatus.FREE, null, null);
        assertSlot(store.bedSlot(moved.bedId(), TODAY, 82), SlotStatus.BUFFER, orderId, moved.holdId());
    }

    @Test
    void changeTherapistFreesOldAndPrefersOriginalBed() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        long other = bookPaid(store, service, "rs-bed1", T1, START_1930);
        assertThat(store.findOrderById(other).bedId()).isEqualTo(BED1);
        long orderId = bookPaid(store, service, "rs-swap-t", T2, START_1930);
        assertThat(store.findOrderById(orderId).bedId()).isEqualTo(BED2);
        long oldHold = store.findOrderById(orderId).holdId();

        RescheduleResult moved = service.reschedule(cmd("rs-swap-1", orderId, TODAY, START_1600, T1));

        assertThat(moved.therapistId()).isEqualTo(T1);
        assertThat(moved.bedId()).isEqualTo(BED2);
        assertThat(moved.startSlotNo()).isEqualTo(START_1600);
        assertThat(moved.holdId()).isNotEqualTo(oldHold);
        for (int slot = 78; slot <= 82; slot++) {
            assertSlot(store.therapistSlot(T2, TODAY, slot), SlotStatus.FREE, null, null);
            assertThat(store.occupancies)
                    .doesNotContainKey(InMemorySlotOccupyStore.okey(ResourceType.THERAPIST, T2, TODAY, slot));
        }
        for (int slot = 64; slot <= 67; slot++) {
            assertSlot(store.therapistSlot(T1, TODAY, slot), SlotStatus.BOOKED, orderId, moved.holdId());
        }
        assertSlot(store.therapistSlot(T1, TODAY, 68), SlotStatus.BUFFER, orderId, moved.holdId());
        for (int slot = 64; slot <= 68; slot++) {
            assertSlot(store.bedSlot(BED2, TODAY, slot),
                    slot == 68 ? SlotStatus.BUFFER : SlotStatus.BOOKED, orderId, moved.holdId());
        }
        assertThat(store.findOrderById(other).bedId()).isEqualTo(BED1);
        assertThat(store.jobByHold(moved.holdId())).isNull();
    }

    @Test
    void targetBusyLeavesOriginalOccupancy() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        long orderId = bookPaid(store, service, "rs-busy-src", T1, START_1600);
        long blocker = bookPaid(store, service, "rs-busy-dst", T2, START_1930);
        long oldHold = store.findOrderById(orderId).holdId();
        int occ = store.occupancyCount();

        store.therapistSlot(T1, TODAY, 80).status = SlotStatus.BOOKED;
        store.therapistSlot(T1, TODAY, 80).orderId = blocker;

        assertThatThrownBy(() -> service.reschedule(cmd("rs-busy-1", orderId, TODAY, START_1930, T1)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.SLOT_UNAVAILABLE);

        assertThat(store.occupancyCount()).isEqualTo(occ);
        assertThat(store.findOrderById(orderId).holdId()).isEqualTo(oldHold);
        assertThat(store.findOrderById(orderId).startSlotNo()).isEqualTo(START_1600);
        for (int slot = 64; slot <= 68; slot++) {
            assertThat(store.therapistSlot(T1, TODAY, slot).orderId).isEqualTo(orderId);
            assertThat(store.therapistSlot(T1, TODAY, slot).holdId).isEqualTo(oldHold);
            assertThat(store.occupancies)
                    .containsKey(InMemorySlotOccupyStore.okey(ResourceType.THERAPIST, T1, TODAY, slot));
        }
    }

    @Test
    void statusNotBookedIs40904() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        LockNewResult pending = service.lockNew(OccupyFixtures.cmd("rs-pend", T1, START_1930));
        assertThatThrownBy(() -> service.reschedule(cmd("rs-pend-1", pending.orderId(), TODAY, START_1600, T1)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.ILLEGAL_TRANSITION);

        service.confirmPaidSlots(pending.orderId());
        store.setOrderStatus(pending.orderId(), "CHECKED_IN");
        int occ = store.occupancyCount();
        assertThatThrownBy(() -> service.reschedule(cmd("rs-ci-1", pending.orderId(), TODAY, START_1600, T1)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.ILLEGAL_TRANSITION);
        assertThat(store.occupancyCount()).isEqualTo(occ);
        assertThat(store.findOrderById(pending.orderId()).startSlotNo()).isEqualTo(START_1930);
    }

    @Test
    void otherStoreTherapistSlotsRejected() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        long orderId = bookPaid(store, service, "rs-store", T1, START_1930);
        long oldHold = store.findOrderById(orderId).holdId();
        store.seedTherapistSlots(T2, 9_999_000_000_000_001L, TODAY, START_1600, START_1600 + 5, SlotStatus.FREE);

        assertThatThrownBy(() -> service.reschedule(cmd("rs-store-1", orderId, TODAY, START_1600, T2)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.ILLEGAL_TRANSITION);
        assertThat(store.findOrderById(orderId).holdId()).isEqualTo(oldHold);
        assertThat(store.findOrderById(orderId).therapistId()).isEqualTo(T1);
        assertThat(store.findOrderById(orderId).startSlotNo()).isEqualTo(START_1930);
    }

    @Test
    void priceMismatchRejected() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        long orderId = bookPaid(store, service, "rs-price", T1, START_1930);
        store.seedSlotOverride(T1, TODAY, START_2000, 1L);
        long oldHold = store.findOrderById(orderId).holdId();

        assertThatThrownBy(() -> service.reschedule(cmd("rs-price-1", orderId, TODAY, START_2000, T1)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.ILLEGAL_TRANSITION);
        assertThat(store.findOrderById(orderId).holdId()).isEqualTo(oldHold);
        assertThat(store.findOrderById(orderId).startSlotNo()).isEqualTo(START_1930);
        assertSlot(store.therapistSlot(T1, TODAY, 82), SlotStatus.BUFFER, orderId, oldHold);
    }

    @Test
    void originalBedBusyFallsBackUnderLock() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        long orderId = bookPaid(store, service, "rs-bed-fb", T1, START_1930);
        assertThat(store.findOrderById(orderId).bedId()).isEqualTo(BED1);
        store.bedSlot(BED1, TODAY, 83).status = SlotStatus.BOOKED;
        store.bedSlot(BED1, TODAY, 84).status = SlotStatus.BOOKED;

        RescheduleResult moved = service.reschedule(cmd("rs-bed-fb-1", orderId, TODAY, START_2000, T1));

        assertThat(moved.bedId()).isEqualTo(BED2);
        assertPaidWindow(store, T1, BED2, TODAY, START_2000, orderId, moved.holdId());
        assertSlot(store.bedSlot(BED1, TODAY, 78), SlotStatus.FREE, null, null);
        assertThat(store.slotPinAttempts).contains(InMemorySlotOccupyStore.bkey(BED1, TODAY, 83));
        assertThat(store.slotPinAttempts).contains(InMemorySlotOccupyStore.bkey(BED2, TODAY, 80));
    }

    private static long bookPaid(
            InMemorySlotOccupyStore store, SlotOccupyService service, String requestId, long therapist, int start) {
        LockNewResult locked = service.lockNew(OccupyFixtures.cmd(requestId, therapist, start));
        service.confirmPaidSlots(locked.orderId());
        store.setOrderStatus(locked.orderId(), SlotOccupyService.ORDER_BOOKED);
        return locked.orderId();
    }

    private static RescheduleCommand cmd(String requestId, long orderId, LocalDate date, int start, long therapistId) {
        return new RescheduleCommand(requestId, orderId, date, start, therapistId, 1L);
    }

    private static void assertPaidWindow(
            InMemorySlotOccupyStore store, long therapistId, long bedId, LocalDate date,
            int start, long orderId, long holdId) {
        for (int slot = start; slot < start + 4; slot++) {
            assertSlot(store.therapistSlot(therapistId, date, slot), SlotStatus.BOOKED, orderId, holdId);
            assertSlot(store.bedSlot(bedId, date, slot), SlotStatus.BOOKED, orderId, holdId);
        }
        assertSlot(store.therapistSlot(therapistId, date, start + 4), SlotStatus.BUFFER, orderId, holdId);
        assertSlot(store.bedSlot(bedId, date, start + 4), SlotStatus.BUFFER, orderId, holdId);
    }

    private static void assertSlot(MutableSlot slot, String status, Long orderId, Long holdId) {
        assertThat(slot.status).isEqualTo(status);
        assertThat(slot.orderId).isEqualTo(orderId);
        assertThat(slot.holdId).isEqualTo(holdId);
        if (SlotStatus.FREE.equals(status)) {
            assertThat(slot.lockExpireAt).isNull();
        }
    }
}
