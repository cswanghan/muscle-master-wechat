package com.jisuodashi.inventory.persist;

import com.jisuodashi.inventory.SlotGenerateStore.BedRef;
import com.jisuodashi.inventory.SlotGenerateStore.ExistingTherapistSlot;
import com.jisuodashi.inventory.SlotGenerateStore.ScheduleExceptionView;
import com.jisuodashi.inventory.SlotGenerateStore.ScheduleTemplateView;
import com.jisuodashi.inventory.SlotGenerateStore.StoreRef;
import com.jisuodashi.inventory.SlotGenerateStore.TherapistRef;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface InventoryGenerateMapper {

    @Select("""
            SELECT id, home_store_id AS homeStoreId
              FROM therapist
             WHERE status = 1 AND deleted_at IS NULL
             ORDER BY id
            """)
    List<TherapistRef> listActiveTherapists();

    @Select("""
            SELECT id, business_start AS businessStart, business_end AS businessEnd
              FROM store
             WHERE status = 1 AND deleted_at IS NULL
             ORDER BY id
            """)
    List<StoreRef> listActiveStores();

    @Select("""
            SELECT id, store_id AS storeId
              FROM bed
             WHERE status = 1 AND deleted_at IS NULL
             ORDER BY id
            """)
    List<BedRef> listActiveBeds();

    @Select("""
            SELECT id, therapist_id AS therapistId, store_id AS storeId, weekday,
                   start_time AS startTime, end_time AS endTime,
                   effective_from AS effectiveFrom, effective_to AS effectiveTo, status
              FROM schedule_template
             WHERE therapist_id = #{therapistId}
               AND weekday = #{weekday}
               AND status = 1
               AND effective_from <= #{date}
               AND (effective_to IS NULL OR effective_to >= #{date})
             ORDER BY id
            """)
    List<ScheduleTemplateView> listActiveTemplates(
            @Param("therapistId") long therapistId,
            @Param("weekday") int weekday,
            @Param("date") LocalDate date);

    @Select("""
            SELECT id, therapist_id AS therapistId, store_id AS storeId,
                   except_date AS exceptDate, type,
                   start_time AS startTime, end_time AS endTime, status
              FROM schedule_exception
             WHERE therapist_id = #{therapistId}
               AND except_date = #{date}
               AND status = 'APPROVED'
             ORDER BY id
            """)
    List<ScheduleExceptionView> listApprovedExceptions(
            @Param("therapistId") long therapistId,
            @Param("date") LocalDate date);

    @Select("""
            SELECT slot_no AS slotNo, store_id AS storeId, status
              FROM therapist_slot
             WHERE therapist_id = #{therapistId} AND slot_date = #{date}
            """)
    List<ExistingSlotRow> listTherapistSlots(
            @Param("therapistId") long therapistId,
            @Param("date") LocalDate date);

    @Select("""
            SELECT EXISTS(
                SELECT 1 FROM therapist_slot
                 WHERE slot_date >= #{from} AND slot_date <= #{to}
            )
            """)
    boolean existsTherapistSlotInRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Insert("""
            INSERT IGNORE INTO therapist_slot
              (id, therapist_id, store_id, slot_date, slot_no, status,
               order_id, hold_id, lock_expire_at, price_override_fen, created_at, updated_at)
            VALUES
              (#{id}, #{therapistId}, #{storeId}, #{slotDate}, #{slotNo}, #{status},
               NULL, NULL, NULL, NULL, #{now}, #{now})
            """)
    int insertTherapistSlotIgnore(
            @Param("id") long id,
            @Param("therapistId") long therapistId,
            @Param("storeId") long storeId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNo") int slotNo,
            @Param("status") String status,
            @Param("now") LocalDateTime now);

    @Insert("""
            INSERT IGNORE INTO bed_slot
              (id, bed_id, store_id, slot_date, slot_no, status,
               order_id, hold_id, lock_expire_at, created_at, updated_at)
            VALUES
              (#{id}, #{bedId}, #{storeId}, #{slotDate}, #{slotNo}, #{status},
               NULL, NULL, NULL, #{now}, #{now})
            """)
    int insertBedSlotIgnore(
            @Param("id") long id,
            @Param("bedId") long bedId,
            @Param("storeId") long storeId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNo") int slotNo,
            @Param("status") String status,
            @Param("now") LocalDateTime now);

    @Insert("""
            INSERT IGNORE INTO human_task
              (id, workflow_instance_id, order_id, task_type, biz_key, title, detail,
               status, assignee_role, store_id, created_at, resolved_at, resolved_by)
            VALUES
              (#{id}, NULL, NULL, #{taskType}, #{bizKey}, #{title}, #{detail},
               'OPEN', 'STORE_MANAGER', #{storeId}, #{now}, NULL, NULL)
            """)
    int insertHumanTaskIgnore(
            @Param("id") long id,
            @Param("taskType") String taskType,
            @Param("bizKey") String bizKey,
            @Param("title") String title,
            @Param("detail") String detail,
            @Param("storeId") Long storeId,
            @Param("now") LocalDateTime now);

    class ExistingSlotRow {
        private int slotNo;
        private long storeId;
        private String status;

        public int getSlotNo() {
            return slotNo;
        }

        public void setSlotNo(int slotNo) {
            this.slotNo = slotNo;
        }

        public long getStoreId() {
            return storeId;
        }

        public void setStoreId(long storeId) {
            this.storeId = storeId;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        ExistingTherapistSlot toView() {
            return new ExistingTherapistSlot(storeId, status);
        }
    }
}
