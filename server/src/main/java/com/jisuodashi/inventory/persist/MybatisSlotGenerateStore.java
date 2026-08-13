package com.jisuodashi.inventory.persist;

import com.jisuodashi.common.AppClock;
import com.jisuodashi.inventory.SlotGenerateStore;
import com.jisuodashi.inventory.persist.InventoryGenerateMapper.ExistingSlotRow;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class MybatisSlotGenerateStore implements SlotGenerateStore {

    private final InventoryGenerateMapper mapper;
    private final AppClock clock;

    public MybatisSlotGenerateStore(InventoryGenerateMapper mapper, AppClock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public List<TherapistRef> listActiveTherapists() {
        return mapper.listActiveTherapists();
    }

    @Override
    public List<StoreRef> listActiveStores() {
        return mapper.listActiveStores();
    }

    @Override
    public List<BedRef> listActiveBeds() {
        return mapper.listActiveBeds();
    }

    @Override
    public List<ScheduleTemplateView> listActiveTemplates(long therapistId, int weekday, LocalDate date) {
        return mapper.listActiveTemplates(therapistId, weekday, date);
    }

    @Override
    public List<ScheduleExceptionView> listApprovedExceptions(long therapistId, LocalDate date) {
        return mapper.listApprovedExceptions(therapistId, date);
    }

    @Override
    public Map<Integer, ExistingTherapistSlot> listTherapistSlots(long therapistId, LocalDate date) {
        Map<Integer, ExistingTherapistSlot> out = new LinkedHashMap<>();
        for (ExistingSlotRow row : mapper.listTherapistSlots(therapistId, date)) {
            out.put(row.getSlotNo(), row.toView());
        }
        return out;
    }

    @Override
    public boolean existsTherapistSlotInRange(LocalDate from, LocalDate to) {
        return mapper.existsTherapistSlotInRange(from, to);
    }

    @Override
    public boolean insertTherapistSlotIgnore(TherapistSlotInsert row) {
        LocalDateTime now = clock.now();
        return mapper.insertTherapistSlotIgnore(
                row.id(), row.therapistId(), row.storeId(), row.slotDate(), row.slotNo(), row.status(), now) > 0;
    }

    @Override
    public boolean insertBedSlotIgnore(BedSlotInsert row) {
        LocalDateTime now = clock.now();
        return mapper.insertBedSlotIgnore(
                row.id(), row.bedId(), row.storeId(), row.slotDate(), row.slotNo(), row.status(), now) > 0;
    }

    @Override
    public void insertHumanTaskIgnore(HumanTaskInsert row) {
        mapper.insertHumanTaskIgnore(
                row.id(), row.taskType(), row.bizKey(), row.title(), row.detail(), row.storeId(), clock.now());
    }
}
