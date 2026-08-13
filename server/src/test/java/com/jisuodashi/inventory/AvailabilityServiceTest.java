package com.jisuodashi.inventory;

import com.jisuodashi.catalog.DemoCatalogIds;
import com.jisuodashi.catalog.InMemoryCatalogRepository;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvailabilityServiceTest {

    private static final LocalDate DAY = InMemoryAvailabilityStore.DEMO_DATE;
    private static final long STORE = DemoCatalogIds.STORE;
    private static final long T1 = DemoCatalogIds.THERAPIST_LIN;
    private static final long T2 = DemoCatalogIds.THERAPIST_CHEN;
    private static final long T3 = DemoCatalogIds.THERAPIST_ZHOU;
    private static final long P60 = DemoCatalogIds.PROJECT_P60;
    private static final long BED1 = InMemoryAvailabilityStore.BED1;
    private static final long BED2 = InMemoryAvailabilityStore.BED2;

    @Test
    void startsAreOnlyFreeBookableWindows() {
        AvailabilityDtos.Availability res = demo().query(STORE, DAY, P60, T1, true);

        assertThat(res.slotMinutes()).isEqualTo(15);
        assertThat(res.occupySlots()).isEqualTo(5);
        assertThat(res.therapists()).hasSize(1);
        AvailabilityDtos.Therapist lin = res.therapists().getFirst();
        List<Integer> starts = lin.starts().stream().map(AvailabilityDtos.Start::slotNo).toList();
        assertThat(starts).contains(40, 51, 64, 73, 83);
        assertThat(starts).doesNotContain(52, 56, 74, 78, 82);
        assertThat(lin.starts()).allMatch(s -> s.priceFen() == 19800);
        assertThat(lin.starts()).extracting(AvailabilityDtos.Start::start)
                .contains("10:00", "20:45");

        assertThat(lin.blocks()).extracting(AvailabilityDtos.Block::state)
                .contains(SlotStatus.FREE, SlotStatus.REST, SlotStatus.LOCKED);
        assertThat(stateAt(lin, 40)).isEqualTo(SlotStatus.FREE);
        assertThat(stateAt(lin, 56)).isEqualTo(SlotStatus.REST);
        assertThat(stateAt(lin, 78)).isEqualTo(SlotStatus.LOCKED);
        assertThat(starts).allMatch(slot -> SlotStatus.FREE.equals(stateAt(lin, slot)));
    }

    @Test
    void lockedAndBookedAreNeverStarts() {
        AvailabilityDtos.Availability res = demo().query(STORE, DAY, P60, null, true);
        AvailabilityDtos.Therapist zhou = therapist(res, T3);
        assertThat(zhou.starts().stream().map(AvailabilityDtos.Start::slotNo)).doesNotContain(40, 41, 42, 43, 44);
        assertThat(stateAt(zhou, 40)).isEqualTo(SlotStatus.BOOKED);
        assertThat(zhou.starts().getFirst().slotNo()).isEqualTo(45);

        AvailabilityDtos.Therapist lin = therapist(res, T1);
        assertThat(lin.starts().stream().map(AvailabilityDtos.Start::slotNo)).doesNotContain(78, 79, 80, 81, 82);
    }

    @Test
    void occupancyOnFreeStatusIsBusy() {
        InMemoryAvailabilityStore store = InMemoryAvailabilityStore.blank();
        store.seedTherapistSlots(T1, DAY, 40, 88, SlotStatus.FREE);
        store.seedBedSlots(BED1, DAY, 40, 88, SlotStatus.FREE);
        store.seedOccupancy(ResourceType.THERAPIST, T1, DAY, 50, 51);

        AvailabilityDtos.Availability res = service(store).query(STORE, DAY, P60, T1, true);
        List<Integer> starts = res.therapists().getFirst().starts().stream()
                .map(AvailabilityDtos.Start::slotNo).toList();
        assertThat(starts).doesNotContain(46, 47, 48, 49, 50);
        assertThat(starts).contains(40, 45, 51);
        assertThat(stateAt(res.therapists().getFirst(), 50)).isEqualTo(SlotStatus.LOCKED);
    }

    @Test
    void needsSomeBedWindowIdle() {
        InMemoryAvailabilityStore store = InMemoryAvailabilityStore.blank();
        store.seedTherapistSlots(T1, DAY, 40, 88, SlotStatus.FREE);
        store.seedBedSlots(BED1, DAY, 40, 88, SlotStatus.BOOKED);
        store.seedBedSlots(BED2, DAY, 40, 88, SlotStatus.LOCKED);
        store.seedOccupancy(ResourceType.BED, BED1, DAY, 40, 88);
        store.seedOccupancy(ResourceType.BED, BED2, DAY, 40, 88);

        AvailabilityDtos.Availability res = service(store).query(STORE, DAY, P60, T1, false);
        assertThat(res.therapists()).isEmpty();

        store.setBedStatus(BED2, DAY, 78, SlotStatus.FREE);
        store.setBedStatus(BED2, DAY, 79, SlotStatus.FREE);
        store.setBedStatus(BED2, DAY, 80, SlotStatus.FREE);
        store.setBedStatus(BED2, DAY, 81, SlotStatus.FREE);
        store.setBedStatus(BED2, DAY, 82, SlotStatus.FREE);
        store.clearOccupancy(ResourceType.BED, BED2, DAY, 78);
        store.clearOccupancy(ResourceType.BED, BED2, DAY, 79);
        store.clearOccupancy(ResourceType.BED, BED2, DAY, 80);
        store.clearOccupancy(ResourceType.BED, BED2, DAY, 81);
        store.clearOccupancy(ResourceType.BED, BED2, DAY, 82);

        AvailabilityDtos.Availability after = service(store).query(STORE, DAY, P60, T1, false);
        assertThat(after.therapists().getFirst().starts())
                .extracting(AvailabilityDtos.Start::slotNo)
                .containsExactly(78);
    }

    @Test
    void d13UsesSlotOverride() {
        InMemoryAvailabilityStore store = InMemoryAvailabilityStore.blank();
        store.seedTherapistSlots(T1, DAY, 40, 88, SlotStatus.FREE);
        store.seedBedSlots(BED1, DAY, 40, 88, SlotStatus.FREE);
        store.setTherapistOverride(T1, DAY, 40, 15000L);

        AvailabilityDtos.Availability res = service(store).query(STORE, DAY, P60, T1, false);
        assertThat(res.therapists().getFirst().starts().getFirst().priceFen()).isEqualTo(15000);
        assertThat(res.therapists().getFirst().starts().get(1).priceFen()).isEqualTo(19800);
    }

    @Test
    void missingStoreOrProjectIs404() {
        AvailabilityService svc = demo();
        assertThatThrownBy(() -> svc.query(1L, DAY, P60, null, false))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.NOT_FOUND);
        assertThatThrownBy(() -> svc.query(STORE, DAY, 1L, null, false))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.NOT_FOUND);
    }

    @Test
    void therapistFilterAndChenAllFree() {
        AvailabilityDtos.Availability res = demo().query(STORE, DAY, P60, T2, false);
        assertThat(res.therapists()).hasSize(1);
        assertThat(res.therapists().getFirst().therapistId()).isEqualTo(String.valueOf(T2));
        assertThat(res.therapists().getFirst().starts()).hasSize(44);
        assertThat(res.therapists().getFirst().starts().getFirst().slotNo()).isEqualTo(40);
        assertThat(res.therapists().getFirst().starts().getLast().slotNo()).isEqualTo(83);
    }

    private static AvailabilityService demo() {
        return service(new InMemoryAvailabilityStore());
    }

    private static AvailabilityService service(InMemoryAvailabilityStore store) {
        return new AvailabilityService(
                store,
                new InMemoryCatalogRepository(),
                new AvailabilityCache(Duration.ofSeconds(30), Clock.systemUTC()));
    }

    private static AvailabilityDtos.Therapist therapist(AvailabilityDtos.Availability res, long id) {
        return res.therapists().stream()
                .filter(t -> t.therapistId().equals(String.valueOf(id)))
                .findFirst()
                .orElseThrow();
    }

    private static String stateAt(AvailabilityDtos.Therapist t, int slotNo) {
        return t.blocks().stream()
                .filter(b -> b.slotNo() == slotNo)
                .map(AvailabilityDtos.Block::state)
                .findFirst()
                .orElseThrow();
    }
}
