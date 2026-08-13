package com.jisuodashi.inventory;

import com.jisuodashi.common.AppClock;
import com.jisuodashi.inventory.SlotOccupyStore.BedRef;
import com.jisuodashi.inventory.SlotOccupyStore.ProjectRef;
import com.jisuodashi.inventory.SlotOccupyStore.TherapistRef;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicLong;

/** V3 demo IDs + 10:00–22:00 FREE calendar for lockNew tests. */
public final class OccupyFixtures {

    public static final long STORE = DemoFixtures.STORE;
    static final long ROOM = 3_100_000_000_000_000_101L;
    public static final long T1 = DemoFixtures.T1;
    static final long T2 = DemoFixtures.T2;
    static final long T3 = DemoFixtures.T3;
    public static final long BED1 = DemoFixtures.BED1;
    static final long BED2 = DemoFixtures.BED2;
    static final long P60 = 3_100_000_000_000_000_501L;
    public static final long CUSTOMER = 8_100_000_000_000_000_001L;
    static final LocalDate TODAY = DemoFixtures.TODAY;
    static final int OPEN_SLOT = 40;
    static final int CLOSE_SLOT = 88;
    public static final int START_1930 = 78;
    static final int START_2000 = 80;

    private OccupyFixtures() {
    }

    public static InMemorySlotOccupyStore demoStore() {
        return demoStore(2);
    }

    static InMemorySlotOccupyStore demoStore(int bedCount) {
        InMemorySlotOccupyStore store = new InMemorySlotOccupyStore();
        store.seedProject(new ProjectRef(P60, "全身推拿放松", 60, 15, 19800));
        store.seedTherapist(new TherapistRef(T1, STORE));
        store.seedTherapist(new TherapistRef(T2, STORE));
        store.seedTherapist(new TherapistRef(T3, STORE));
        store.seedBed(new BedRef(BED1, STORE, ROOM, 1));
        if (bedCount > 1) {
            store.seedBed(new BedRef(BED2, STORE, ROOM, 2));
        }
        for (long therapist : new long[] {T1, T2, T3}) {
            store.seedTherapistSlots(therapist, STORE, TODAY, OPEN_SLOT, CLOSE_SLOT, SlotStatus.FREE);
        }
        store.seedBedSlots(BED1, STORE, TODAY, OPEN_SLOT, CLOSE_SLOT, SlotStatus.FREE);
        if (bedCount > 1) {
            store.seedBedSlots(BED2, STORE, TODAY, OPEN_SLOT, CLOSE_SLOT, SlotStatus.FREE);
        }
        return store;
    }

    public static SlotOccupyService service(InMemorySlotOccupyStore store) {
        return service(store, new InMemoryTherapistDayLock());
    }

    static SlotOccupyService service(InMemorySlotOccupyStore store, TherapistDayLock dayLock) {
        AtomicLong ids = new AtomicLong(9_100_000_000_000_000_000L);
        AppClock clock = new AppClock(java.time.Clock.fixed(
                TODAY.atTime(LocalTime.of(19, 0)).atZone(AppClock.SHANGHAI).toInstant(), AppClock.SHANGHAI));
        return new SlotOccupyService(store, dayLock, ids::incrementAndGet, clock);
    }

    public static LockNewCommand cmd(String requestId, long therapistId, int startSlotNo) {
        return new LockNewCommand(requestId, CUSTOMER, STORE, therapistId, P60, TODAY, startSlotNo, "MINI_C");
    }
}
