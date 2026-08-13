package com.jisuodashi.inventory;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Planned {@code (store_id, FREE|REST)} per slot_no for one therapist-day.
 * Conflicted slot_nos are omitted from {@link #slots()} and listed in {@link #conflicts()}.
 */
public record TherapistDayPlan(
        long therapistId,
        LocalDate date,
        Map<Integer, PlannedSlot> slots,
        Map<Integer, Set<Long>> conflicts
) {

    public TherapistDayPlan {
        slots = Map.copyOf(slots);
        conflicts = Map.copyOf(conflicts);
    }

    public List<Integer> slotNos() {
        return List.copyOf(new TreeMap<>(slots).keySet());
    }

    public record PlannedSlot(long storeId, String status) {
    }
}
