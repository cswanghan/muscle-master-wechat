package com.jisuodashi.inventory;

import com.jisuodashi.inventory.SlotGenerateStore.ExistingTherapistSlot;
import com.jisuodashi.inventory.SlotGenerateStore.TherapistSlotInsert;
import com.jisuodashi.job.SlotGenerateJob;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static com.jisuodashi.inventory.DemoFixtures.SLOTS_PER_SHIFT;
import static com.jisuodashi.inventory.DemoFixtures.STORE;
import static com.jisuodashi.inventory.DemoFixtures.SUPPORT_STORE;
import static com.jisuodashi.inventory.DemoFixtures.T1;
import static com.jisuodashi.inventory.DemoFixtures.T2;
import static com.jisuodashi.inventory.DemoFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;

class SlotGenerateServiceTest {

    @Test
    void firstRunGeneratesTodayThroughTodayPlus15() {
        InMemorySlotGenerateStore store = DemoFixtures.demoStore();
        SlotGenerateResult result = DemoFixtures.service(store).generate(TODAY);

        LocalDate horizon = TODAY.plusDays(SlotGenerateService.HORIZON_DAYS);
        assertThat(result.firstRun()).isTrue();
        assertThat(result.from()).isEqualTo(TODAY);
        assertThat(result.to()).isEqualTo(horizon);
        int therapistDays = 3 * 16;
        assertThat(result.therapistInserted()).isEqualTo(therapistDays * SLOTS_PER_SHIFT);
        assertThat(result.bedInserted()).isEqualTo(2 * 16 * SLOTS_PER_SHIFT);
        assertThat(result.freeWritten()).isEqualTo(therapistDays * SLOTS_PER_SHIFT);
        assertThat(result.restWritten()).isZero();
        assertThat(result.conflicts()).isZero();
        assertThat(store.therapistSlots.values())
                .allMatch(s -> SlotStatus.FREE.equals(s.status()))
                .allMatch(s -> !s.slotDate().isBefore(TODAY) && !s.slotDate().isAfter(horizon));
        assertThat(store.bedSlots.values()).allMatch(s -> SlotStatus.FREE.equals(s.status()));
        assertNoOccupation(store);
    }

    @Test
    void secondRunIsIdempotentInsertIgnore() {
        InMemorySlotGenerateStore store = DemoFixtures.demoStore();
        SlotGenerateService service = DemoFixtures.service(store);
        service.generate(TODAY);
        int before = store.therapistSlots.size();
        SlotGenerateResult second = service.generate(TODAY);
        assertThat(second.firstRun()).isFalse();
        assertThat(second.therapistInserted()).isZero();
        assertThat(second.therapistIgnored()).isEqualTo(before);
        assertThat(store.therapistSlots).hasSize(before);
        assertThat(store.humanTasks).isEmpty();
    }

    @Test
    void partialLeaveWritesRestOnlyOnThoseSlots() {
        InMemorySlotGenerateStore store = DemoFixtures.demoStore();
        store.exceptions.add(DemoFixtures.leave(1, T1, TODAY, LocalTime.of(14, 0), LocalTime.of(16, 0)));
        SlotGenerateResult result = DemoFixtures.service(store).generate(TODAY);

        assertThat(result.restWritten()).isEqualTo(8);
        assertThat(result.freeWritten()).isEqualTo(3 * 16 * SLOTS_PER_SHIFT - 8);
        for (int slot = 56; slot < 64; slot++) {
            assertThat(store.therapistSlot(T1, TODAY, slot).status()).isEqualTo(SlotStatus.REST);
        }
        assertThat(store.therapistSlot(T1, TODAY, 40).status()).isEqualTo(SlotStatus.FREE);
        assertThat(store.therapistSlot(T1, TODAY, 64).status()).isEqualTo(SlotStatus.FREE);
        assertThat(store.therapistSlot(T1, TODAY.plusDays(1), 56).status()).isEqualTo(SlotStatus.FREE);
        assertNoOccupation(store);
    }

    @Test
    void supportWritesExceptionStoreNotHomeStore() {
        InMemorySlotGenerateStore store = DemoFixtures.demoStore();
        store.stores.add(new SlotGenerateStore.StoreRef(SUPPORT_STORE, DemoFixtures.OPEN, DemoFixtures.CLOSE));
        store.exceptions.add(DemoFixtures.support(
                2, T2, SUPPORT_STORE, TODAY, LocalTime.of(18, 0), LocalTime.of(22, 0)));
        DemoFixtures.service(store).generate(TODAY);

        assertThat(store.therapistSlot(T2, TODAY, 40).storeId()).isEqualTo(STORE);
        assertThat(store.therapistSlot(T2, TODAY, 72).storeId()).isEqualTo(SUPPORT_STORE);
        assertThat(store.therapistSlot(T2, TODAY, 87).storeId()).isEqualTo(SUPPORT_STORE);
        assertThat(store.therapistSlot(T2, TODAY, 72).status()).isEqualTo(SlotStatus.FREE);
    }

    @Test
    void plannedStoreConflictWritesHumanTaskAndSkipsSlot() {
        InMemorySlotGenerateStore store = DemoFixtures.demoStore();
        store.stores.add(new SlotGenerateStore.StoreRef(SUPPORT_STORE, DemoFixtures.OPEN, DemoFixtures.CLOSE));
        store.templates.add(new SlotGenerateStore.ScheduleTemplateView(
                88, T1, SUPPORT_STORE, 5, LocalTime.of(10, 0), LocalTime.of(12, 0),
                TODAY, TODAY, 1));

        SlotGenerateResult result = DemoFixtures.service(store).generate(TODAY);
        assertThat(result.conflicts()).isEqualTo(8);
        assertThat(store.humanTasks).hasSize(8);
        assertThat(store.humanTasks.values())
                .allMatch(t -> SlotGenerateService.TASK_STORE_CONFLICT.equals(t.taskType()))
                .allMatch(t -> t.bizKey().startsWith("gsc:" + T1 + ":" + TODAY + ":"));
        for (int slot = 40; slot < 48; slot++) {
            assertThat(store.therapistSlot(T1, TODAY, slot)).isNull();
        }
        assertThat(store.therapistSlot(T1, TODAY, 48)).isNotNull();
        assertNoOccupation(store);
    }

    @Test
    void existingDifferentStoreDoesNotInsertIgnoreSilently() {
        InMemorySlotGenerateStore store = DemoFixtures.demoStore();
        store.insertTherapistSlotIgnore(new TherapistSlotInsert(
                1, T1, SUPPORT_STORE, TODAY, 40, SlotStatus.FREE));

        SlotGenerateResult result = DemoFixtures.service(store).generate(TODAY);
        assertThat(result.conflicts()).isEqualTo(1);
        ExistingTherapistSlot kept = store.listTherapistSlots(T1, TODAY).get(40);
        assertThat(kept.storeId()).isEqualTo(SUPPORT_STORE);
        assertThat(store.humanTasks).hasSize(1);
        assertThat(store.humanTasks.values().iterator().next().detail()).contains("\"storeIds\":[");
    }

    @Test
    void jobDelegatesToServiceWithoutWritingBuffer() {
        InMemorySlotGenerateStore store = DemoFixtures.demoStore();
        SlotGenerateJob job = new SlotGenerateJob(DemoFixtures.service(store));
        SlotGenerateResult result = job.run(TODAY);
        assertThat(result.therapistInserted()).isPositive();
        assertThat(store.therapistSlots.values())
                .extracting(TherapistSlotInsert::status)
                .containsOnly(SlotStatus.FREE);
        assertThat(store.occupancyWrites.get()).isZero();
    }

    private static void assertNoOccupation(InMemorySlotGenerateStore store) {
        assertThat(store.occupancyWrites.get()).isZero();
        assertThat(store.therapistSlots.values())
                .extracting(TherapistSlotInsert::status)
                .doesNotContain(SlotStatus.BUFFER, SlotStatus.LOCKED, SlotStatus.BOOKED);
        assertThat(store.bedSlots.values())
                .extracting(SlotGenerateStore.BedSlotInsert::status)
                .containsOnly(SlotStatus.FREE);
    }
}
