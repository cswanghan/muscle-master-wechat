package com.jisuodashi.inventory;

import com.jisuodashi.inventory.SlotGenerateStore.ScheduleExceptionView;
import com.jisuodashi.inventory.SlotGenerateStore.ScheduleTemplateView;
import com.jisuodashi.inventory.TherapistDayPlan.PlannedSlot;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure planned-slot computation. Does not persist, lock, or write occupancy / BUFFER.
 *
 * <p>Templates produce FREE at {@code template.store_id}. APPROVED SUPPORT overrides
 * {@code store_id} to {@code exception.store_id}. APPROVED LEAVE flips those slot_nos
 * to REST (partial-day = only {@code [start,end)}; empty times = every planned slot).
 * Two different store_ids for the same slot_no become a conflict and the slot is dropped.
 */
public class SlotPlanner {

    public static final String TYPE_LEAVE = "LEAVE";
    public static final String TYPE_SUPPORT = "SUPPORT";
    public static final String STATUS_APPROVED = "APPROVED";

    public TherapistDayPlan plan(
            long therapistId,
            LocalDate date,
            List<ScheduleTemplateView> templates,
            List<ScheduleExceptionView> exceptions
    ) {
        Map<Integer, Long> storeBySlot = new LinkedHashMap<>();
        Map<Integer, Set<Long>> conflicts = new LinkedHashMap<>();

        for (ScheduleTemplateView tpl : templates) {
            if (tpl.status() != 1 || tpl.weekday() != date.getDayOfWeek().getValue()) {
                continue;
            }
            if (tpl.effectiveFrom() != null && date.isBefore(tpl.effectiveFrom())) {
                continue;
            }
            if (tpl.effectiveTo() != null && date.isAfter(tpl.effectiveTo())) {
                continue;
            }
            mergeStores(storeBySlot, conflicts, SlotTimes.range(tpl.startTime(), tpl.endTime()), tpl.storeId());
        }

        Map<Integer, Long> supportBySlot = new LinkedHashMap<>();
        Map<Integer, Set<Long>> supportConflicts = new LinkedHashMap<>();
        for (ScheduleExceptionView ex : exceptions) {
            if (!TYPE_SUPPORT.equals(ex.type()) || !STATUS_APPROVED.equals(ex.status())) {
                continue;
            }
            if (ex.storeId() == null) {
                continue;
            }
            List<Integer> range = exceptionRange(ex, storeBySlot.keySet());
            mergeStores(supportBySlot, supportConflicts, range, ex.storeId());
        }
        for (Map.Entry<Integer, Long> e : supportBySlot.entrySet()) {
            int slot = e.getKey();
            storeBySlot.put(slot, e.getValue());
            conflicts.remove(slot);
        }
        for (Map.Entry<Integer, Set<Long>> e : supportConflicts.entrySet()) {
            storeBySlot.remove(e.getKey());
            conflicts.put(e.getKey(), Set.copyOf(e.getValue()));
        }

        Map<Integer, String> statusBySlot = new LinkedHashMap<>();
        for (Integer slot : storeBySlot.keySet()) {
            statusBySlot.put(slot, SlotStatus.FREE);
        }
        for (ScheduleExceptionView ex : exceptions) {
            if (!TYPE_LEAVE.equals(ex.type()) || !STATUS_APPROVED.equals(ex.status())) {
                continue;
            }
            List<Integer> range = exceptionRange(ex, storeBySlot.keySet());
            for (int slot : range) {
                if (storeBySlot.containsKey(slot)) {
                    statusBySlot.put(slot, SlotStatus.REST);
                }
            }
        }

        Map<Integer, PlannedSlot> planned = new LinkedHashMap<>();
        for (Map.Entry<Integer, Long> e : storeBySlot.entrySet()) {
            planned.put(e.getKey(), new PlannedSlot(e.getValue(), statusBySlot.get(e.getKey())));
        }
        return new TherapistDayPlan(therapistId, date, planned, conflicts);
    }

    private static void mergeStores(
            Map<Integer, Long> storeBySlot,
            Map<Integer, Set<Long>> conflicts,
            List<Integer> slots,
            long storeId
    ) {
        for (int slot : slots) {
            Set<Long> alreadyConflicted = conflicts.get(slot);
            if (alreadyConflicted != null) {
                alreadyConflicted.add(storeId);
                continue;
            }
            Long existing = storeBySlot.get(slot);
            if (existing == null) {
                storeBySlot.put(slot, storeId);
            } else if (existing != storeId) {
                storeBySlot.remove(slot);
                Set<Long> ids = new LinkedHashSet<>();
                ids.add(existing);
                ids.add(storeId);
                conflicts.put(slot, ids);
            }
        }
    }

    /**
     * Empty times on LEAVE/SUPPORT mean the whole planned day (or nothing if no planned slots).
     * Partial-day uses half-open {@code [start, end)}.
     */
    private static List<Integer> exceptionRange(ScheduleExceptionView ex, Set<Integer> plannedSlots) {
        LocalTime start = ex.startTime();
        LocalTime end = ex.endTime();
        if (start == null || end == null) {
            return plannedSlots.stream().sorted().toList();
        }
        return SlotTimes.range(start, end);
    }
}
