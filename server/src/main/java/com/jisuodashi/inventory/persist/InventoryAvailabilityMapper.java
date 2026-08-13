package com.jisuodashi.inventory.persist;

import com.jisuodashi.inventory.AvailabilityStore.BedSlotView;
import com.jisuodashi.inventory.AvailabilityStore.OccupancyView;
import com.jisuodashi.inventory.AvailabilityStore.TherapistSlotView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface InventoryAvailabilityMapper {

    @Select("""
            SELECT therapist_id AS therapistId, slot_no AS slotNo, status,
                   price_override_fen AS priceOverrideFen
              FROM therapist_slot
             WHERE store_id = #{storeId} AND slot_date = #{date}
             ORDER BY therapist_id, slot_no
            """)
    List<TherapistSlotView> listTherapistSlots(@Param("storeId") long storeId, @Param("date") LocalDate date);

    @Select("""
            SELECT bed_id AS bedId, slot_no AS slotNo, status
              FROM bed_slot
             WHERE store_id = #{storeId} AND slot_date = #{date}
             ORDER BY bed_id, slot_no
            """)
    List<BedSlotView> listBedSlots(@Param("storeId") long storeId, @Param("date") LocalDate date);

    @Select("""
            SELECT o.resource_type AS resourceType, o.resource_id AS resourceId, o.slot_no AS slotNo
              FROM slot_occupancy o
             WHERE o.slot_date = #{date}
               AND (
                    (o.resource_type = 'THERAPIST' AND EXISTS (
                        SELECT 1 FROM therapist_slot t
                         WHERE t.therapist_id = o.resource_id
                           AND t.store_id = #{storeId}
                           AND t.slot_date = #{date}
                           AND t.slot_no = o.slot_no
                    ))
                 OR (o.resource_type = 'BED' AND EXISTS (
                        SELECT 1 FROM bed_slot b
                         WHERE b.bed_id = o.resource_id
                           AND b.store_id = #{storeId}
                           AND b.slot_date = #{date}
                           AND b.slot_no = o.slot_no
                    ))
               )
            """)
    List<OccupancyView> listOccupancies(@Param("storeId") long storeId, @Param("date") LocalDate date);
}
