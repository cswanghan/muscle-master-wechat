package com.jisuodashi.inventory;

import com.jisuodashi.inventory.SlotGenerateStore.ScheduleExceptionView;
import com.jisuodashi.inventory.SlotGenerateStore.ScheduleTemplateView;
import com.jisuodashi.inventory.TherapistDayPlan.PlannedSlot;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static com.jisuodashi.inventory.DemoFixtures.STORE;
import static com.jisuodashi.inventory.DemoFixtures.SUPPORT_STORE;
import static com.jisuodashi.inventory.DemoFixtures.T1;
import static com.jisuodashi.inventory.DemoFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;

class SlotPlannerTest {

    private final SlotPlanner planner = new SlotPlanner();

    @Test
    void templateSlotsAreFreeOnDutyStore() {
        TherapistDayPlan plan = planner.plan(T1, TODAY, List.of(weekdayTemplate(STORE)), List.of());
        assertThat(plan.slots()).hasSize(48);
        assertThat(plan.slots().values())
                .allMatch(s -> SlotStatus.FREE.equals(s.status()) && s.storeId() == STORE);
        assertThat(plan.conflicts()).isEmpty();
        assertThat(plan.slots().keySet()).doesNotContain(39, 88);
    }

    @Test
    void partialDayLeaveOnlyThoseSlotNosBecomeRest() {
        var leave = DemoFixtures.leave(1, T1, TODAY, LocalTime.of(14, 0), LocalTime.of(16, 0));
        TherapistDayPlan plan = planner.plan(T1, TODAY, List.of(weekdayTemplate(STORE)), List.of(leave));
        assertRest(plan, 56, 64);
        assertThat(plan.slots().get(40).status()).isEqualTo(SlotStatus.FREE);
        assertThat(plan.slots().get(55).status()).isEqualTo(SlotStatus.FREE);
        assertThat(plan.slots().get(64).status()).isEqualTo(SlotStatus.FREE);
        assertThat(count(plan, SlotStatus.REST)).isEqualTo(8);
        assertThat(count(plan, SlotStatus.FREE)).isEqualTo(40);
        assertThat(plan.slots().values()).noneMatch(s -> SlotStatus.BUFFER.equals(s.status()));
    }

    @Test
    void fullDayLeaveWhenTimesNullMarksEveryPlannedSlotRest() {
        var leave = DemoFixtures.leave(1, T1, TODAY, null, null);
        TherapistDayPlan plan = planner.plan(T1, TODAY, List.of(weekdayTemplate(STORE)), List.of(leave));
        assertThat(plan.slots()).hasSize(48);
        assertThat(plan.slots().values()).allMatch(s -> SlotStatus.REST.equals(s.status()));
    }

    @Test
    void pendingLeaveIsIgnored() {
        var pending = new ScheduleExceptionView(
                1, T1, null, TODAY, "LEAVE", LocalTime.of(14, 0), LocalTime.of(16, 0), "PENDING");
        TherapistDayPlan plan = planner.plan(T1, TODAY, List.of(weekdayTemplate(STORE)), List.of(pending));
        assertThat(count(plan, SlotStatus.REST)).isZero();
        assertThat(count(plan, SlotStatus.FREE)).isEqualTo(48);
    }

    @Test
    void supportUsesExceptionStoreIdNotHomeStore() {
        var support = DemoFixtures.support(2, T1, SUPPORT_STORE, TODAY, LocalTime.of(18, 0), LocalTime.of(22, 0));
        TherapistDayPlan plan = planner.plan(T1, TODAY, List.of(weekdayTemplate(STORE)), List.of(support));
        assertThat(plan.slots().get(40).storeId()).isEqualTo(STORE);
        assertThat(plan.slots().get(71).storeId()).isEqualTo(STORE);
        for (int slot = 72; slot < 88; slot++) {
            PlannedSlot planned = plan.slots().get(slot);
            assertThat(planned.storeId()).isEqualTo(SUPPORT_STORE);
            assertThat(planned.status()).isEqualTo(SlotStatus.FREE);
        }
        assertThat(plan.conflicts()).isEmpty();
    }

    @Test
    void twoTemplatesDifferentStoresConflictAndDropTheSlot() {
        var a = weekdayTemplate(STORE);
        var b = new ScheduleTemplateView(
                99, T1, SUPPORT_STORE, 5, LocalTime.of(10, 0), LocalTime.of(12, 0),
                LocalDate.of(2026, 1, 1), null, 1);
        TherapistDayPlan plan = planner.plan(T1, TODAY, List.of(a, b), List.of());
        for (int slot = 40; slot < 48; slot++) {
            assertThat(plan.slots()).doesNotContainKey(slot);
            assertThat(plan.conflicts().get(slot)).containsExactlyInAnyOrder(STORE, SUPPORT_STORE);
        }
        assertThat(plan.slots().get(48).storeId()).isEqualTo(STORE);
        assertThat(plan.conflicts()).hasSize(8);
    }

    @Test
    void twoSupportStoresConflict() {
        var s1 = DemoFixtures.support(1, T1, STORE, TODAY, LocalTime.of(10, 0), LocalTime.of(12, 0));
        var s2 = DemoFixtures.support(2, T1, SUPPORT_STORE, TODAY, LocalTime.of(10, 0), LocalTime.of(12, 0));
        TherapistDayPlan plan = planner.plan(T1, TODAY, List.of(), List.of(s1, s2));
        assertThat(plan.slots()).isEmpty();
        assertThat(plan.conflicts()).hasSize(8);
        assertThat(plan.conflicts().get(40)).containsExactlyInAnyOrder(STORE, SUPPORT_STORE);
    }

    private static ScheduleTemplateView weekdayTemplate(long storeId) {
        return new ScheduleTemplateView(
                1, T1, storeId, 5, DemoFixtures.OPEN, DemoFixtures.CLOSE,
                DemoFixtures.EFFECTIVE, null, 1);
    }

    private static void assertRest(TherapistDayPlan plan, int fromInclusive, int toExclusive) {
        for (int slot = fromInclusive; slot < toExclusive; slot++) {
            assertThat(plan.slots().get(slot).status()).as("slot %s", slot).isEqualTo(SlotStatus.REST);
        }
    }

    private static long count(TherapistDayPlan plan, String status) {
        return plan.slots().values().stream().filter(s -> status.equals(s.status())).count();
    }
}
