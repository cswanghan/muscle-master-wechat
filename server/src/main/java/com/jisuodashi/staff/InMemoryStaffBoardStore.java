package com.jisuodashi.staff;

import com.jisuodashi.catalog.DemoFixtures;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
@Profile("dev")
public class InMemoryStaffBoardStore implements StaffBoardStore {

    static final long ROOM = 3_100_000_000_000_000_101L;
    static final long BED1 = 3_100_000_000_000_000_201L;
    static final long BED2 = 3_100_000_000_000_000_202L;

    /** Derived from the fixtures; a hardcoded pair rendered every other bed as "床位". */
    private static final Map<Long, String> ROOMS = Map.of(
            DemoFixtures.ROOM_MAIN, "一号房",
            DemoFixtures.ROOM_EAST, "一号房");
    private static final Map<Long, String> BEDS = DemoFixtures.beds().stream()
            .collect(Collectors.toUnmodifiableMap(DemoFixtures.BedSeed::bedId, DemoFixtures.BedSeed::name));

    private final InMemorySlotOccupyStore occupy;

    public InMemoryStaffBoardStore(InMemorySlotOccupyStore occupy) {
        this.occupy = occupy;
    }

    @Override
    public List<BookingOrderRef> listTherapistDayOrders(long therapistId, LocalDate date) {
        return occupy.listTherapistDayOrders(therapistId, date);
    }

    @Override
    public List<SlotGlance> listTherapistDaySlots(long therapistId, LocalDate date) {
        return occupy.listTherapistDaySlots(therapistId, date).stream()
                .map(slot -> new SlotGlance(slot.slotNo(), slot.status(), slot.orderId()))
                .toList();
    }

    @Override
    public String firstProjectName(long orderId) {
        return occupy.firstProjectName(orderId);
    }

    @Override
    public RoomBedNames roomBed(long roomId, long bedId) {
        return new RoomBedNames(
                ROOMS.getOrDefault(roomId, "房间"),
                BEDS.getOrDefault(bedId, "床位"));
    }

    @Override
    public long countCompletedForCustomer(long customerId) {
        return occupy.countCompletedForCustomer(customerId);
    }
}
