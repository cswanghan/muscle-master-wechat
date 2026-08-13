package com.jisuodashi.inventory;

import java.time.LocalDate;
import java.util.List;

/** Read port for the store-day calendar. Writes stay on {@link SlotOccupyStore}. */
public interface AvailabilityStore {

    List<TherapistSlotView> listTherapistSlots(long storeId, LocalDate date);

    List<BedSlotView> listBedSlots(long storeId, LocalDate date);

    List<OccupancyView> listOccupancies(long storeId, LocalDate date);

    record TherapistSlotView(long therapistId, int slotNo, String status, Long priceOverrideFen) {
    }

    record BedSlotView(long bedId, int slotNo, String status) {
    }

    record OccupancyView(String resourceType, long resourceId, int slotNo) {
    }
}
