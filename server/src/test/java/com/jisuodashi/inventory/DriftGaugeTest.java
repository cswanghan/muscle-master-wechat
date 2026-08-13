package com.jisuodashi.inventory;

import com.jisuodashi.common.AppClock;
import com.jisuodashi.inventory.SlotOccupyStore.OccupancyInsert;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class DriftGaugeTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 14);

    @Test
    void occupancyOnFreeSlotIncrementsDrift() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        AppClock clock = new AppClock(Clock.fixed(
                DAY.atTime(LocalTime.of(19, 0)).atZone(AppClock.SHANGHAI).toInstant(), AppClock.SHANGHAI));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        InventoryDriftGauge gauge = new InventoryDriftGauge(store, clock, meters);

        int baseline = gauge.scrape();
        assertThat(meters.get(InventoryDriftGauge.METRIC).gauge().value()).isEqualTo(baseline);

        store.beginWork();
        store.insertOccupancy(new OccupancyInsert(
                99L, ResourceType.THERAPIST, OccupyFixtures.T1, DAY, 40, 1L, 1L, LocalDateTime.of(DAY, LocalTime.NOON)));
        store.commitWork();

        int after = gauge.scrape();
        assertThat(after).isEqualTo(baseline + 1);
        assertThat(meters.get(InventoryDriftGauge.METRIC).gauge().value()).isEqualTo(after);
    }

    @Test
    void lockedWithoutOccupancyIncrementsDrift() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        AppClock clock = new AppClock();
        InventoryDriftGauge gauge = new InventoryDriftGauge(store, clock, new SimpleMeterRegistry());
        int baseline = gauge.scrape();
        store.therapistSlot(OccupyFixtures.T1, DAY, 50).status = SlotStatus.LOCKED;
        assertThat(gauge.scrape()).isEqualTo(baseline + 1);
    }
}
