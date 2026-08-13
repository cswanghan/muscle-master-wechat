package com.jisuodashi.inventory.persist;

import com.jisuodashi.inventory.AvailabilityStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
@Profile("!dev")
public class MybatisAvailabilityStore implements AvailabilityStore {

    private final InventoryAvailabilityMapper mapper;

    public MybatisAvailabilityStore(InventoryAvailabilityMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<TherapistSlotView> listTherapistSlots(long storeId, LocalDate date) {
        return mapper.listTherapistSlots(storeId, date);
    }

    @Override
    public List<BedSlotView> listBedSlots(long storeId, LocalDate date) {
        return mapper.listBedSlots(storeId, date);
    }

    @Override
    public List<OccupancyView> listOccupancies(long storeId, LocalDate date) {
        return mapper.listOccupancies(storeId, date);
    }
}
