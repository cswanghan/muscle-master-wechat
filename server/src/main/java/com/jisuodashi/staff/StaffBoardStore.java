package com.jisuodashi.staff;

import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;

import java.time.LocalDate;
import java.util.List;

/** Therapist today-board reads. Inventory occupy store stays the write path. */
public interface StaffBoardStore {

    List<BookingOrderRef> listTherapistDayOrders(long therapistId, LocalDate date);

    List<SlotGlance> listTherapistDaySlots(long therapistId, LocalDate date);

    String firstProjectName(long orderId);

    RoomBedNames roomBed(long roomId, long bedId);

    long countCompletedForCustomer(long customerId);

    record SlotGlance(int slotNo, String state, Long orderId) {
    }

    record RoomBedNames(String roomName, String bedName) {
    }
}
