package com.jisuodashi.inventory;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.inventory.InMemorySlotOccupyStore.MutableSlot;
import com.jisuodashi.inventory.SlotOccupyStore.OccupancyInsert;
import com.jisuodashi.inventory.SlotOccupyStore.TherapistRef;
import com.jisuodashi.staff.InMemoryTreatmentNoteRepository;
import com.jisuodashi.staff.ServiceRecord;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static com.jisuodashi.inventory.OccupyFixtures.BED1;
import static com.jisuodashi.inventory.OccupyFixtures.START_1930;
import static com.jisuodashi.inventory.OccupyFixtures.T1;
import static com.jisuodashi.inventory.OccupyFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SwapTherapistServiceTest {

    static final long T2 = OccupyFixtures.T2;

    @Test
    void checkedInSwapMovesRemainAndLeavesBed() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        InMemoryTreatmentNoteRepository notes = new InMemoryTreatmentNoteRepository();
        SlotOccupyService service = OccupyFixtures.service(store);
        service.setTreatmentNotes(notes);
        LockNewResult locked = service.lockNew(OccupyFixtures.cmd("sw-ci", T1, START_1930));
        service.confirmPaidSlots(locked.orderId());
        store.setOrderStatus(locked.orderId(), "CHECKED_IN");
        Map<String, OccupancyInsert> bedsBefore = bedOccupancy(store);
        var pinsBefore = java.util.Set.copyOf(store.slotPinAttempts);

        SwapTherapistResult swapped = service.swapTherapist("sw-ci-1", locked.orderId(), T2, "指定技师请假");

        assertThat(swapped.oldTherapistId()).isEqualTo(T1);
        assertThat(swapped.newTherapistId()).isEqualTo(T2);
        assertThat(swapped.fromSlotNo()).isEqualTo(START_1930);
        assertThat(swapped.endSlotNo()).isEqualTo(83);
        assertThat(swapped.replay()).isFalse();
        assertThat(store.findOrderById(locked.orderId()).therapistId()).isEqualTo(T2);
        assertThat(store.findOrderById(locked.orderId()).status()).isEqualTo("CHECKED_IN");

        for (int slot = 78; slot <= 82; slot++) {
            MutableSlot neu = store.therapistSlot(T2, TODAY, slot);
            MutableSlot old = store.therapistSlot(T1, TODAY, slot);
            assertThat(old.status).isEqualTo(SlotStatus.FREE);
            assertThat(old.orderId).isNull();
            assertThat(old.holdId).isNull();
            if (slot < 82) {
                assertThat(neu.status).isEqualTo(SlotStatus.BOOKED);
            } else {
                assertThat(neu.status).isEqualTo(SlotStatus.BUFFER);
            }
            assertThat(neu.orderId).isEqualTo(locked.orderId());
            assertThat(neu.holdId).isEqualTo(locked.holdId());
            assertThat(store.occupancies).containsKey(
                    InMemorySlotOccupyStore.okey(ResourceType.THERAPIST, T2, TODAY, slot));
            assertThat(store.occupancies).doesNotContainKey(
                    InMemorySlotOccupyStore.okey(ResourceType.THERAPIST, T1, TODAY, slot));
        }
        assertThat(bedOccupancy(store)).isEqualTo(bedsBefore);
        for (int slot = 78; slot <= 82; slot++) {
            assertThat(store.bedSlot(BED1, TODAY, slot).orderId).isEqualTo(locked.orderId());
        }
        assertThat(store.slotPinAttempts.stream().filter(k -> k.startsWith("B|")).collect(Collectors.toSet()))
                .isEqualTo(pinsBefore.stream().filter(k -> k.startsWith("B|")).collect(Collectors.toSet()));
        assertThat(notes.listServiceRecords(locked.orderId())).isEmpty();
        assertThat(notes.findByOrderId(locked.orderId()))
                .noneMatch(n -> n.content().contains("中途换师"));

        SwapTherapistResult replay = service.swapTherapist("sw-ci-1", locked.orderId(), T2, "指定技师请假");
        assertThat(replay.replay()).isTrue();
        assertThat(replay.orderId()).isEqualTo(locked.orderId());
        assertThat(store.findOrderById(locked.orderId()).therapistId()).isEqualTo(T2);
    }

    @Test
    void inServiceSwapKeepsPastSlotsAndAddsSegment() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        InMemoryTreatmentNoteRepository notes = new InMemoryTreatmentNoteRepository();
        SlotOccupyService service = serviceAt(store, LocalTime.of(20, 0));
        service.setTreatmentNotes(notes);
        LockNewResult locked = service.lockNew(OccupyFixtures.cmd("sw-is", T1, START_1930));
        service.confirmPaidSlots(locked.orderId());
        store.setOrderStatus(locked.orderId(), "IN_SERVICE");
        notes.insertServiceRecord(
                1L, locked.orderId(), T1, OccupyFixtures.CUSTOMER, OccupyFixtures.STORE,
                TODAY.atTime(19, 30).atZone(AppClock.SHANGHAI).toInstant());
        Map<String, OccupancyInsert> bedsBefore = bedOccupancy(store);

        SwapTherapistResult swapped = service.swapTherapist("sw-is-1", locked.orderId(), T2, "中途换师");

        assertThat(swapped.fromSlotNo()).isEqualTo(80);
        assertThat(store.findOrderById(locked.orderId()).therapistId()).isEqualTo(T2);
        assertThat(store.therapistSlot(T1, TODAY, 78).status).isEqualTo(SlotStatus.BOOKED);
        assertThat(store.therapistSlot(T1, TODAY, 79).status).isEqualTo(SlotStatus.BOOKED);
        assertThat(store.therapistSlot(T1, TODAY, 78).orderId).isEqualTo(locked.orderId());
        assertThat(store.occupancies).containsKey(
                InMemorySlotOccupyStore.okey(ResourceType.THERAPIST, T1, TODAY, 78));
        for (int slot = 80; slot <= 82; slot++) {
            assertThat(store.therapistSlot(T1, TODAY, slot).status).isEqualTo(SlotStatus.FREE);
            assertThat(store.occupancies).doesNotContainKey(
                    InMemorySlotOccupyStore.okey(ResourceType.THERAPIST, T1, TODAY, slot));
            MutableSlot neu = store.therapistSlot(T2, TODAY, slot);
            assertThat(neu.status).isEqualTo(slot < 82 ? SlotStatus.BOOKED : SlotStatus.BUFFER);
            assertThat(neu.orderId).isEqualTo(locked.orderId());
        }
        assertThat(store.therapistSlot(T2, TODAY, 78).status).isEqualTo(SlotStatus.FREE);
        assertThat(bedOccupancy(store)).isEqualTo(bedsBefore);

        List<ServiceRecord> segments = notes.listServiceRecords(locked.orderId());
        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).therapistId()).isEqualTo(T1);
        assertThat(segments.get(0).endedAt()).isNotNull();
        assertThat(segments.get(1).therapistId()).isEqualTo(T2);
        assertThat(segments.get(1).endedAt()).isNull();
        assertThat(notes.findByOrderId(locked.orderId()))
                .anyMatch(n -> n.content().contains("中途换师")
                        && n.storeId() == OccupyFixtures.STORE
                        && n.therapistId() == T2);
    }

    @Test
    void swapWritesNewTherapistHomeStoreId() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        long homeOther = 3_199_000_000_000_000_001L;
        store.seedTherapist(new TherapistRef(T2, homeOther));
        SlotOccupyService service = OccupyFixtures.service(store);
        LockNewResult locked = service.lockNew(OccupyFixtures.cmd("sw-home", T1, START_1930));
        service.confirmPaidSlots(locked.orderId());
        store.setOrderStatus(locked.orderId(), "CHECKED_IN");
        Map<String, OccupancyInsert> bedsBefore = bedOccupancy(store);
        assertThat(store.orders.get(locked.orderId()).therapistHomeStoreId()).isEqualTo(OccupyFixtures.STORE);

        service.swapTherapist("sw-home-1", locked.orderId(), T2, "x");

        assertThat(store.orders.get(locked.orderId()).therapistHomeStoreId()).isEqualTo(homeOther);
        assertThat(store.findOrderById(locked.orderId()).storeId()).isEqualTo(OccupyFixtures.STORE);
        assertThat(store.findOrderById(locked.orderId()).therapistId()).isEqualTo(T2);
        assertThat(bedOccupancy(store)).isEqualTo(bedsBefore);
    }

    @Test
    void inServiceAfterEndOrNextDayDoesNotMovePastSlots() {
        InMemorySlotOccupyStore afterEnd = OccupyFixtures.demoStore();
        InMemoryTreatmentNoteRepository notes = new InMemoryTreatmentNoteRepository();
        SlotOccupyService evening = serviceAt(afterEnd, TODAY, LocalTime.of(21, 0));
        evening.setTreatmentNotes(notes);
        LockNewResult late = evening.lockNew(OccupyFixtures.cmd("sw-late", T1, START_1930));
        evening.confirmPaidSlots(late.orderId());
        afterEnd.setOrderStatus(late.orderId(), "IN_SERVICE");
        notes.insertServiceRecord(
                2L, late.orderId(), T1, OccupyFixtures.CUSTOMER, OccupyFixtures.STORE,
                TODAY.atTime(19, 30).atZone(AppClock.SHANGHAI).toInstant());

        SwapTherapistResult lateSwap = evening.swapTherapist("sw-late-1", late.orderId(), T2, "x");
        assertThat(lateSwap.fromSlotNo()).isEqualTo(83);
        for (int slot = 78; slot <= 82; slot++) {
            assertThat(afterEnd.therapistSlot(T1, TODAY, slot).orderId).isEqualTo(late.orderId());
            assertThat(afterEnd.therapistSlot(T2, TODAY, slot).status).isEqualTo(SlotStatus.FREE);
        }
        assertThat(afterEnd.findOrderById(late.orderId()).therapistId()).isEqualTo(T2);
        assertThat(notes.listServiceRecords(late.orderId())).hasSize(2);

        InMemorySlotOccupyStore nextDay = OccupyFixtures.demoStore();
        InMemoryTreatmentNoteRepository nextNotes = new InMemoryTreatmentNoteRepository();
        SlotOccupyService morning = serviceAt(nextDay, TODAY.plusDays(1), LocalTime.of(8, 0));
        morning.setTreatmentNotes(nextNotes);
        LockNewResult overnight = morning.lockNew(OccupyFixtures.cmd("sw-next", T1, START_1930));
        morning.confirmPaidSlots(overnight.orderId());
        nextDay.setOrderStatus(overnight.orderId(), "IN_SERVICE");
        nextNotes.insertServiceRecord(
                3L, overnight.orderId(), T1, OccupyFixtures.CUSTOMER, OccupyFixtures.STORE,
                TODAY.atTime(19, 30).atZone(AppClock.SHANGHAI).toInstant());

        SwapTherapistResult nextSwap = morning.swapTherapist("sw-next-1", overnight.orderId(), T2, "x");
        assertThat(nextSwap.fromSlotNo()).isEqualTo(83);
        for (int slot = 78; slot <= 82; slot++) {
            assertThat(nextDay.therapistSlot(T1, TODAY, slot).orderId).isEqualTo(overnight.orderId());
            assertThat(nextDay.therapistSlot(T2, TODAY, slot).status).isEqualTo(SlotStatus.FREE);
        }
        assertThat(nextDay.findOrderById(overnight.orderId()).therapistId()).isEqualTo(T2);
    }

    @Test
    void busyNewTherapistReturns40901WithoutPartialUpdate() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        LockNewResult locked = service.lockNew(OccupyFixtures.cmd("sw-busy", T1, START_1930));
        service.confirmPaidSlots(locked.orderId());
        store.setOrderStatus(locked.orderId(), "CHECKED_IN");
        store.therapistSlot(T2, TODAY, 80).status = SlotStatus.BOOKED;
        Map<String, OccupancyInsert> occBefore = Map.copyOf(store.occupancies);

        assertThatThrownBy(() -> service.swapTherapist("sw-busy-1", locked.orderId(), T2, "x"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.SLOT_UNAVAILABLE);

        assertThat(store.findOrderById(locked.orderId()).therapistId()).isEqualTo(T1);
        assertThat(store.therapistSlot(T1, TODAY, 78).status).isEqualTo(SlotStatus.BOOKED);
        assertThat(store.therapistSlot(T2, TODAY, 78).status).isEqualTo(SlotStatus.FREE);
        assertThat(store.therapistSlot(T2, TODAY, 80).status).isEqualTo(SlotStatus.BOOKED);
        assertThat(store.occupancies).isEqualTo(occBefore);
    }

    @Test
    void bookedOrPendingPayReturns40904() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        LockNewResult pending = service.lockNew(OccupyFixtures.cmd("sw-pp", T1, START_1930));
        assertThatThrownBy(() -> service.swapTherapist("sw-pp-1", pending.orderId(), T2, "x"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.ILLEGAL_TRANSITION);

        service.confirmPaidSlots(pending.orderId());
        store.setOrderStatus(pending.orderId(), "BOOKED");
        assertThatThrownBy(() -> service.swapTherapist("sw-bk-1", pending.orderId(), T2, "x"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.ILLEGAL_TRANSITION);
        assertThat(store.findOrderById(pending.orderId()).therapistId()).isEqualTo(T1);
    }

    private static SlotOccupyService serviceAt(InMemorySlotOccupyStore store, LocalTime time) {
        return serviceAt(store, TODAY, time);
    }

    private static SlotOccupyService serviceAt(InMemorySlotOccupyStore store, LocalDate date, LocalTime time) {
        AtomicLong ids = new AtomicLong(9_100_000_000_000_000_000L);
        AppClock clock = new AppClock(Clock.fixed(
                date.atTime(time).atZone(AppClock.SHANGHAI).toInstant(), AppClock.SHANGHAI));
        return new SlotOccupyService(store, new InMemoryTherapistDayLock(), ids::incrementAndGet, clock);
    }

    private static Map<String, OccupancyInsert> bedOccupancy(InMemorySlotOccupyStore store) {
        return store.occupancies.entrySet().stream()
                .filter(e -> e.getKey().startsWith(ResourceType.BED + "|"))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
