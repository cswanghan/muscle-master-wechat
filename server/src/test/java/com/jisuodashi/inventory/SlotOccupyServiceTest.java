package com.jisuodashi.inventory;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.inventory.InMemorySlotOccupyStore.MutableSlot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.jisuodashi.inventory.OccupyFixtures.BED1;
import static com.jisuodashi.inventory.OccupyFixtures.BED2;
import static com.jisuodashi.inventory.OccupyFixtures.START_1930;
import static com.jisuodashi.inventory.OccupyFixtures.T1;
import static com.jisuodashi.inventory.OccupyFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlotOccupyServiceTest {

    @Test
    void lockNewWritesLastBufferOthersLockedAndInsertsOccupancyAndJob() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        List<Long> issued = new ArrayList<>();
        AtomicLong seq = new AtomicLong(100);
        SlotOccupyService service = new SlotOccupyService(
                store, new InMemoryTherapistDayLock(), () -> {
                    long id = seq.incrementAndGet();
                    issued.add(id);
                    return id;
                },
                new com.jisuodashi.common.AppClock(java.time.Clock.fixed(
                        TODAY.atTime(19, 0).atZone(com.jisuodashi.common.AppClock.SHANGHAI).toInstant(),
                        com.jisuodashi.common.AppClock.SHANGHAI)));

        LockNewResult result = service.lockNew(OccupyFixtures.cmd("req-happy", T1, START_1930));

        assertThat(issued.get(1)).isEqualTo(result.orderId());
        assertThat(issued.get(2)).isEqualTo(result.holdId());
        assertThat(result.status()).isEqualTo(SlotOccupyService.ORDER_PENDING_PAY);
        assertThat(result.bufferSlots()).isEqualTo(1);
        assertThat(result.startSlotNo()).isEqualTo(78);
        assertThat(result.endSlotNo()).isEqualTo(83);
        assertThat(result.bedId()).isEqualTo(BED1);
        assertThat(result.orderNo()).startsWith("JS20260814");
        assertThat(result.lockExpireAt()).contains("19:15");

        for (int slot = 78; slot <= 81; slot++) {
            MutableSlot row = store.therapistSlot(T1, TODAY, slot);
            assertThat(row.status).isEqualTo(SlotStatus.LOCKED);
            assertThat(row.orderId).isEqualTo(result.orderId());
            assertThat(row.holdId).isEqualTo(result.holdId());
        }
        assertThat(store.therapistSlot(T1, TODAY, 82).status).isEqualTo(SlotStatus.BUFFER);
        assertThat(store.bedSlot(BED1, TODAY, 82).status).isEqualTo(SlotStatus.BUFFER);
        assertThat(store.therapistSlot(T1, TODAY, 83).status).isEqualTo(SlotStatus.FREE);

        assertThat(store.occupancies).hasSize(10);
        assertThat(store.occupancies.values())
                .allMatch(o -> o.orderId() == result.orderId() && o.holdId() == result.holdId());
        assertThat(store.orders).containsKey(result.orderId());
        assertThat(store.delayedJobs).hasSize(1);
        assertThat(store.delayedJobs.get(0).jobType()).isEqualTo(SlotOccupyService.JOB_RELEASE_LOCK);
        assertThat(store.delayedJobs.get(0).bizKey()).isEqualTo("hold:" + result.holdId());
        assertThat(store.delayedJobs.get(0).runAt()).isEqualTo(TODAY.atTime(19, 15));
        assertThat(store.idem(SlotOccupyService.SCOPE_BOOKING, "req-happy").status).isEqualTo("DONE");
    }

    @Test
    void skipsBusyBedAndDoesNotLockIt() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        for (int slot = 78; slot <= 82; slot++) {
            store.bedSlot(BED1, TODAY, slot).status = SlotStatus.BOOKED;
        }
        LockNewResult result = OccupyFixtures.service(store).lockNew(OccupyFixtures.cmd("req-skip", T1, START_1930));

        assertThat(result.bedId()).isEqualTo(BED2);
        for (int slot = 78; slot <= 82; slot++) {
            assertThat(store.bedSlot(BED1, TODAY, slot).status).isEqualTo(SlotStatus.BOOKED);
            assertThat(store.bedSlot(BED1, TODAY, slot).orderId).isNull();
        }
        assertThat(store.bedSlot(BED2, TODAY, 78).status).isEqualTo(SlotStatus.LOCKED);
        assertThat(store.occupancies.keySet())
                .noneMatch(k -> k.startsWith(ResourceType.BED + "|" + BED1 + "|"));
    }

    @Test
    void therapistNotFreeReturns40901() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        store.therapistSlot(T1, TODAY, 80).status = SlotStatus.REST;
        assertThatThrownBy(() -> OccupyFixtures.service(store).lockNew(OccupyFixtures.cmd("req-t", T1, START_1930)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.SLOT_UNAVAILABLE);
        assertThat(store.occupancies).isEmpty();
        assertThat(store.orders).isEmpty();
        assertThat(store.therapistSlot(T1, TODAY, 78).status).isEqualTo(SlotStatus.FREE);
    }

    @Test
    void noFreeBedReturns40902() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        for (int slot = 78; slot <= 82; slot++) {
            store.bedSlot(BED1, TODAY, slot).status = SlotStatus.LOCKED;
            store.bedSlot(BED2, TODAY, slot).status = SlotStatus.LOCKED;
        }
        assertThatThrownBy(() -> OccupyFixtures.service(store).lockNew(OccupyFixtures.cmd("req-bed", T1, START_1930)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.NO_FREE_BED);
        assertThat(store.occupancies).isEmpty();
        assertThat(store.therapistSlot(T1, TODAY, 78).status).isEqualTo(SlotStatus.FREE);
    }

    @Test
    void therapistDayLockMissReturns40903WithoutOccupy() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        TherapistDayLock alwaysBusy = new TherapistDayLock() {
            @Override
            public boolean tryAcquire(long therapistId, java.time.LocalDate date) {
                return false;
            }

            @Override
            public void release(long therapistId, java.time.LocalDate date) {
            }
        };
        assertThatThrownBy(() -> OccupyFixtures.service(store, alwaysBusy)
                        .lockNew(OccupyFixtures.cmd("req-lock", T1, START_1930)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.LOCK_CONFLICT);
        assertThat(store.occupancies).isEmpty();
    }

    @Test
    void blankRequestIdIs40001() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        assertThatThrownBy(() -> OccupyFixtures.service(store).lockNew(OccupyFixtures.cmd("  ", T1, START_1930)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.BAD_REQUEST);
    }
}
