package com.jisuodashi.inventory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory fake. H2 cannot execute V1 MySQL DDL, so generation is tested off-DB. */
final class InMemorySlotGenerateStore implements SlotGenerateStore {

    final List<TherapistRef> therapists = new ArrayList<>();
    final List<StoreRef> stores = new ArrayList<>();
    final List<BedRef> beds = new ArrayList<>();
    final List<ScheduleTemplateView> templates = new ArrayList<>();
    final List<ScheduleExceptionView> exceptions = new ArrayList<>();
    final Map<String, TherapistSlotInsert> therapistSlots = new LinkedHashMap<>();
    final Map<String, BedSlotInsert> bedSlots = new LinkedHashMap<>();
    final Map<String, HumanTaskInsert> humanTasks = new LinkedHashMap<>();
    final AtomicLong occupancyWrites = new AtomicLong();

    @Override
    public List<TherapistRef> listActiveTherapists() {
        return List.copyOf(therapists);
    }

    @Override
    public List<StoreRef> listActiveStores() {
        return List.copyOf(stores);
    }

    @Override
    public List<BedRef> listActiveBeds() {
        return List.copyOf(beds);
    }

    @Override
    public List<ScheduleTemplateView> listActiveTemplates(long therapistId, int weekday, LocalDate date) {
        return templates.stream()
                .filter(t -> t.therapistId() == therapistId && t.weekday() == weekday && t.status() == 1)
                .filter(t -> !date.isBefore(t.effectiveFrom()))
                .filter(t -> t.effectiveTo() == null || !date.isAfter(t.effectiveTo()))
                .toList();
    }

    @Override
    public List<ScheduleExceptionView> listApprovedExceptions(long therapistId, LocalDate date) {
        return exceptions.stream()
                .filter(e -> e.therapistId() == therapistId && e.exceptDate().equals(date))
                .filter(e -> "APPROVED".equals(e.status()))
                .toList();
    }

    @Override
    public Map<Integer, ExistingTherapistSlot> listTherapistSlots(long therapistId, LocalDate date) {
        Map<Integer, ExistingTherapistSlot> out = new LinkedHashMap<>();
        for (TherapistSlotInsert row : therapistSlots.values()) {
            if (row.therapistId() == therapistId && row.slotDate().equals(date)) {
                out.put(row.slotNo(), new ExistingTherapistSlot(row.storeId(), row.status()));
            }
        }
        return out;
    }

    @Override
    public boolean existsTherapistSlotInRange(LocalDate from, LocalDate to) {
        return therapistSlots.values().stream()
                .anyMatch(s -> !s.slotDate().isBefore(from) && !s.slotDate().isAfter(to));
    }

    @Override
    public boolean insertTherapistSlotIgnore(TherapistSlotInsert row) {
        String key = tkey(row.therapistId(), row.slotDate(), row.slotNo());
        if (therapistSlots.containsKey(key)) {
            return false;
        }
        therapistSlots.put(key, row);
        return true;
    }

    @Override
    public boolean insertBedSlotIgnore(BedSlotInsert row) {
        String key = bkey(row.bedId(), row.slotDate(), row.slotNo());
        if (bedSlots.containsKey(key)) {
            return false;
        }
        bedSlots.put(key, row);
        return true;
    }

    @Override
    public void insertHumanTaskIgnore(HumanTaskInsert row) {
        humanTasks.putIfAbsent(row.bizKey(), row);
    }

    static String tkey(long therapistId, LocalDate date, int slotNo) {
        return therapistId + "|" + date + "|" + slotNo;
    }

    static String bkey(long bedId, LocalDate date, int slotNo) {
        return bedId + "|" + date + "|" + slotNo;
    }

    TherapistSlotInsert therapistSlot(long therapistId, LocalDate date, int slotNo) {
        return therapistSlots.get(tkey(therapistId, date, slotNo));
    }
}
