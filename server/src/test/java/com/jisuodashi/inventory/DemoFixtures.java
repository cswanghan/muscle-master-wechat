package com.jisuodashi.inventory;

import com.jisuodashi.common.AppClock;
import com.jisuodashi.inventory.SlotGenerateStore.BedRef;
import com.jisuodashi.inventory.SlotGenerateStore.ScheduleExceptionView;
import com.jisuodashi.inventory.SlotGenerateStore.ScheduleTemplateView;
import com.jisuodashi.inventory.SlotGenerateStore.StoreRef;
import com.jisuodashi.inventory.SlotGenerateStore.TherapistRef;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicLong;

/** V3 demo store IDs and 10:00–22:00 week templates. */
final class DemoFixtures {

    static final long STORE = 3_100_000_000_000_000_001L;
    static final long SUPPORT_STORE = 3_100_000_000_000_000_002L;
    static final long T1 = 3_100_000_000_000_000_401L;
    static final long T2 = 3_100_000_000_000_000_402L;
    static final long T3 = 3_100_000_000_000_000_403L;
    static final long BED1 = 3_100_000_000_000_000_201L;
    static final long BED2 = 3_100_000_000_000_000_202L;
    static final String T1_NAME = "林晓";
    static final String T2_NAME = "陈默";
    static final String T3_NAME = "周可";
    static final LocalTime OPEN = LocalTime.of(10, 0);
    static final LocalTime CLOSE = LocalTime.of(22, 0);
    static final LocalDate EFFECTIVE = LocalDate.of(2026, 1, 1);
    /** Friday; weekday=5 matches V3 templates. */
    static final LocalDate TODAY = LocalDate.of(2026, 8, 14);
    static final int SLOTS_PER_SHIFT = 48;

    private DemoFixtures() {
    }

    static InMemorySlotGenerateStore demoStore() {
        InMemorySlotGenerateStore store = new InMemorySlotGenerateStore();
        store.stores.add(new StoreRef(STORE, OPEN, CLOSE));
        store.therapists.add(new TherapistRef(T1, STORE));
        store.therapists.add(new TherapistRef(T2, STORE));
        store.therapists.add(new TherapistRef(T3, STORE));
        store.beds.add(new BedRef(BED1, STORE));
        store.beds.add(new BedRef(BED2, STORE));
        long tpl = 3_100_000_000_000_000_701L;
        for (long therapist : new long[] {T1, T2, T3}) {
            for (int weekday = 1; weekday <= 7; weekday++) {
                store.templates.add(new ScheduleTemplateView(
                        tpl++, therapist, STORE, weekday, OPEN, CLOSE, EFFECTIVE, null, 1));
            }
        }
        return store;
    }

    static ScheduleExceptionView leave(long id, long therapistId, LocalDate date, LocalTime start, LocalTime end) {
        return new ScheduleExceptionView(id, therapistId, null, date, "LEAVE", start, end, "APPROVED");
    }

    static ScheduleExceptionView support(long id, long therapistId, long storeId, LocalDate date,
                                         LocalTime start, LocalTime end) {
        return new ScheduleExceptionView(id, therapistId, storeId, date, "SUPPORT", start, end, "APPROVED");
    }

    static SlotGenerateService service(InMemorySlotGenerateStore store) {
        AtomicLong ids = new AtomicLong(9_000_000_000_000_000_000L);
        AppClock clock = new AppClock(java.time.Clock.fixed(
                TODAY.atTime(2, 15).atZone(AppClock.SHANGHAI).toInstant(), AppClock.SHANGHAI));
        return new SlotGenerateService(store, ids::incrementAndGet, clock);
    }
}
