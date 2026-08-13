package com.jisuodashi.frontdesk;

import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.inventory.OccupyFixtures;
import com.jisuodashi.inventory.SlotOccupyStore.SlotRow;
import com.jisuodashi.inventory.SlotStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UtilizationServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 14);
    private static final long STORE = OccupyFixtures.STORE;

    @Test
    void mixedStatusesComputeDayAndByHour() {
        List<SlotRow> slots = List.of(
                new SlotRow(40, SlotStatus.BOOKED),
                new SlotRow(41, SlotStatus.FREE),
                new SlotRow(42, SlotStatus.REST),
                new SlotRow(43, SlotStatus.LOCKED),
                new SlotRow(44, SlotStatus.BUFFER),
                new SlotRow(45, SlotStatus.FREE),
                new SlotRow(46, SlotStatus.FREE),
                new SlotRow(47, SlotStatus.FREE));
        FrontDeskDtos.UtilizationResponse res = UtilizationService.compute(STORE, DAY, slots);
        assertThat(res.storeId()).isEqualTo(String.valueOf(STORE));
        assertThat(res.date()).isEqualTo("2026-08-14");
        assertThat(res.rateX10000()).isEqualTo(3 * 10_000 / 7);
        assertThat(res.byHour()).hasSize(2);
        assertThat(res.byHour().get(0).hour()).isEqualTo(10);
        assertThat(res.byHour().get(0).rateX10000()).isEqualTo(2 * 10_000 / 3);
        assertThat(res.byHour().get(1).hour()).isEqualTo(11);
        assertThat(res.byHour().get(1).rateX10000()).isEqualTo(2_500);
    }

    @Test
    void emptyDayIsNullAndByHourEmpty() {
        FrontDeskDtos.UtilizationResponse res = UtilizationService.compute(STORE, LocalDate.of(2020, 1, 1), List.of());
        assertThat(res.rateX10000()).isNull();
        assertThat(res.byHour()).isEmpty();
    }

    @Test
    void restOnlyHourIsNullRate() {
        List<SlotRow> slots = List.of(
                new SlotRow(40, SlotStatus.REST),
                new SlotRow(41, SlotStatus.REST));
        FrontDeskDtos.UtilizationResponse res = UtilizationService.compute(STORE, DAY, slots);
        assertThat(res.rateX10000()).isNull();
        assertThat(res.byHour()).hasSize(1);
        assertThat(res.byHour().getFirst().rateX10000()).isNull();
    }

    @Test
    void inMemoryStoreUsesSlotStoreId() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        store.therapistSlot(OccupyFixtures.T1, DAY, 40).status = SlotStatus.BOOKED;
        store.therapistSlot(OccupyFixtures.T1, DAY, 41).status = SlotStatus.BUFFER;
        store.therapistSlot(OccupyFixtures.T1, DAY, 42).status = SlotStatus.LOCKED;
        store.therapistSlot(OccupyFixtures.T1, DAY, 43).status = SlotStatus.REST;
        List<SlotRow> rows = store.listTherapistSlotsByStore(STORE, DAY);
        FrontDeskDtos.UtilizationResponse res = UtilizationService.compute(STORE, DAY, rows);
        assertThat(res.byHour()).isNotEmpty();
        assertThat(res.rateX10000()).isNotNull();
        assertThat(store.listTherapistSlotsByStore(STORE, LocalDate.of(2020, 1, 1))).isEmpty();
    }
}
