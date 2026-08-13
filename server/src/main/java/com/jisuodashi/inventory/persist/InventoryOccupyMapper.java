package com.jisuodashi.inventory.persist;

import com.jisuodashi.inventory.SlotOccupyStore.BedRef;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import com.jisuodashi.inventory.SlotOccupyStore.IdemRow;
import com.jisuodashi.inventory.SlotOccupyStore.ProjectRef;
import com.jisuodashi.inventory.SlotOccupyStore.SlotRow;
import com.jisuodashi.inventory.SlotOccupyStore.TherapistRef;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface InventoryOccupyMapper {

    @Select("""
            SELECT id, name, duration_minutes AS durationMinutes, buffer_minutes AS bufferMinutes,
                   price_fen AS priceFen
              FROM project
             WHERE id = #{id} AND deleted_at IS NULL
            """)
    ProjectRef loadProject(@Param("id") long id);

    @Select("""
            SELECT id, home_store_id AS homeStoreId
              FROM therapist
             WHERE id = #{id} AND deleted_at IS NULL
            """)
    TherapistRef loadTherapist(@Param("id") long id);

    @Select("""
            SELECT id, store_id AS storeId, room_id AS roomId, sort_no AS sortNo
              FROM bed
             WHERE store_id = #{storeId} AND status = 1 AND deleted_at IS NULL
             ORDER BY sort_no, id
            """)
    List<BedRef> listBeds(@Param("storeId") long storeId);

    @Insert("""
            INSERT INTO idempotency_record
              (id, scope, request_id, status, version, locked_by, response_code, response_body,
               created_at, updated_at, expire_at)
            VALUES
              (#{id}, #{scope}, #{requestId}, #{status}, #{version}, #{lockedBy}, NULL, NULL,
               #{createdAt}, #{updatedAt}, #{expireAt})
            """)
    int insertIdempotency(
            @Param("id") long id,
            @Param("scope") String scope,
            @Param("requestId") String requestId,
            @Param("status") String status,
            @Param("version") int version,
            @Param("lockedBy") String lockedBy,
            @Param("createdAt") LocalDateTime createdAt,
            @Param("updatedAt") LocalDateTime updatedAt,
            @Param("expireAt") LocalDateTime expireAt);

    @Select("""
            SELECT status, version, expire_at AS expireAt, response_body AS responseBody
              FROM idempotency_record
             WHERE scope = #{scope} AND request_id = #{requestId}
             FOR UPDATE
            """)
    IdemRow lockIdempotency(@Param("scope") String scope, @Param("requestId") String requestId);

    @Update("""
            UPDATE idempotency_record
               SET expire_at = #{expireAt}, updated_at = #{now}, version = version + 1,
                   locked_by = #{lockedBy}
             WHERE scope = #{scope} AND request_id = #{requestId}
               AND status = 'PROCESSING' AND expire_at <= #{now} AND version = #{version}
            """)
    int takeoverIdempotency(
            @Param("scope") String scope,
            @Param("requestId") String requestId,
            @Param("version") int version,
            @Param("expireAt") LocalDateTime expireAt,
            @Param("now") LocalDateTime now,
            @Param("lockedBy") String lockedBy);

    @Select("""
            SELECT id, order_no AS orderNo, hold_id AS holdId, bed_id AS bedId, room_id AS roomId,
                   status, lock_expire_at AS lockExpireAt, payable_fen AS payableFen,
                   start_slot_no AS startSlotNo, end_slot_no AS endSlotNo, buffer_slots AS bufferSlots
              FROM booking_order
             WHERE request_id = #{requestId}
            """)
    BookingOrderRef findOrderByRequestId(@Param("requestId") String requestId);

    @Update("""
            UPDATE idempotency_record
               SET status = 'DONE', response_body = #{responseBody}, updated_at = #{now}
             WHERE scope = #{scope} AND request_id = #{requestId}
               AND status = 'PROCESSING' AND version = #{version}
            """)
    int finishIdempotent(
            @Param("scope") String scope,
            @Param("requestId") String requestId,
            @Param("version") int version,
            @Param("responseBody") String responseBody,
            @Param("now") LocalDateTime now);

    @Select("""
            SELECT slot_no AS slotNo, status
              FROM therapist_slot
             WHERE therapist_id = #{therapistId} AND slot_date = #{slotDate}
               AND slot_no IN (${slotNos}) AND status = 'FREE'
             ORDER BY slot_no
             FOR UPDATE
            """)
    List<SlotRow> lockFreeTherapistSlots(
            @Param("therapistId") long therapistId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNos") String slotNos);

    @Select("""
            SELECT slot_no AS slotNo, status
              FROM bed_slot
             WHERE bed_id = #{bedId} AND slot_date = #{slotDate}
               AND slot_no IN (${slotNos}) AND status = 'FREE'
             ORDER BY slot_no
             FOR UPDATE
            """)
    List<SlotRow> lockFreeBedSlots(
            @Param("bedId") long bedId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNos") String slotNos);

    @Select("""
            SELECT EXISTS(
                SELECT 1 FROM slot_occupancy
                 WHERE resource_type = #{resourceType} AND resource_id = #{resourceId}
                   AND slot_date = #{slotDate} AND slot_no IN (${slotNos})
            )
            """)
    boolean occupancyExists(
            @Param("resourceType") String resourceType,
            @Param("resourceId") long resourceId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNos") String slotNos);

    @Update("""
            UPDATE therapist_slot
               SET status = CASE WHEN slot_no >= #{bufferFrom} THEN 'BUFFER' ELSE 'LOCKED' END,
                   order_id = #{orderId}, hold_id = #{holdId}, lock_expire_at = #{expireAt},
                   updated_at = #{now}
             WHERE therapist_id = #{therapistId} AND slot_date = #{slotDate}
               AND slot_no IN (${slotNos}) AND status = 'FREE'
            """)
    int casLockTherapistSlots(
            @Param("therapistId") long therapistId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNos") String slotNos,
            @Param("bufferFrom") int bufferFrom,
            @Param("orderId") long orderId,
            @Param("holdId") long holdId,
            @Param("expireAt") LocalDateTime expireAt,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE bed_slot
               SET status = CASE WHEN slot_no >= #{bufferFrom} THEN 'BUFFER' ELSE 'LOCKED' END,
                   order_id = #{orderId}, hold_id = #{holdId}, lock_expire_at = #{expireAt},
                   updated_at = #{now}
             WHERE bed_id = #{bedId} AND slot_date = #{slotDate}
               AND slot_no IN (${slotNos}) AND status = 'FREE'
            """)
    int casLockBedSlots(
            @Param("bedId") long bedId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNos") String slotNos,
            @Param("bufferFrom") int bufferFrom,
            @Param("orderId") long orderId,
            @Param("holdId") long holdId,
            @Param("expireAt") LocalDateTime expireAt,
            @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO slot_occupancy
              (id, resource_type, resource_id, slot_date, slot_no, order_id, hold_id, created_at)
            VALUES
              (#{id}, #{resourceType}, #{resourceId}, #{slotDate}, #{slotNo}, #{orderId}, #{holdId}, #{createdAt})
            """)
    int insertOccupancy(
            @Param("id") long id,
            @Param("resourceType") String resourceType,
            @Param("resourceId") long resourceId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNo") int slotNo,
            @Param("orderId") long orderId,
            @Param("holdId") long holdId,
            @Param("createdAt") LocalDateTime createdAt);

    @Update("""
            UPDATE bed_slot
               SET status = 'FREE', order_id = NULL, hold_id = NULL, lock_expire_at = NULL,
                   updated_at = #{now}
             WHERE bed_id = #{bedId} AND hold_id = #{holdId}
            """)
    int revertBedHold(
            @Param("bedId") long bedId,
            @Param("holdId") long holdId,
            @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO booking_order
              (id, order_no, request_id, hold_id, add_on_hold_id, customer_id, store_id,
               therapist_id, therapist_home_store_id, bed_id, room_id, status, source,
               service_date, start_slot_no, end_slot_no, buffer_slots,
               origin_price_fen, payable_fen, paid_fen, lock_expire_at,
               version, created_at, updated_at)
            VALUES
              (#{id}, #{orderNo}, #{requestId}, #{holdId}, NULL, #{customerId}, #{storeId},
               #{therapistId}, #{therapistHomeStoreId}, #{bedId}, #{roomId}, #{status}, #{source},
               #{serviceDate}, #{startSlotNo}, #{endSlotNo}, #{bufferSlots},
               #{originPriceFen}, #{payableFen}, 0, #{lockExpireAt},
               0, #{createdAt}, #{createdAt})
            """)
    int insertOrder(
            @Param("id") long id,
            @Param("orderNo") String orderNo,
            @Param("requestId") String requestId,
            @Param("holdId") long holdId,
            @Param("customerId") long customerId,
            @Param("storeId") long storeId,
            @Param("therapistId") long therapistId,
            @Param("therapistHomeStoreId") long therapistHomeStoreId,
            @Param("bedId") long bedId,
            @Param("roomId") long roomId,
            @Param("status") String status,
            @Param("source") String source,
            @Param("serviceDate") LocalDate serviceDate,
            @Param("startSlotNo") int startSlotNo,
            @Param("endSlotNo") int endSlotNo,
            @Param("bufferSlots") int bufferSlots,
            @Param("originPriceFen") long originPriceFen,
            @Param("payableFen") long payableFen,
            @Param("lockExpireAt") LocalDateTime lockExpireAt,
            @Param("createdAt") LocalDateTime createdAt);

    @Insert("""
            INSERT INTO order_item
              (id, order_id, item_type, project_id, project_name, duration_minutes, buffer_minutes,
               quantity, unit_price_fen, amount_fen, start_slot_no, end_slot_no, created_at)
            VALUES
              (#{id}, #{orderId}, #{itemType}, #{projectId}, #{projectName}, #{durationMinutes},
               #{bufferMinutes}, #{quantity}, #{unitPriceFen}, #{amountFen},
               #{startSlotNo}, #{endSlotNo}, #{createdAt})
            """)
    int insertOrderItem(
            @Param("id") long id,
            @Param("orderId") long orderId,
            @Param("itemType") String itemType,
            @Param("projectId") long projectId,
            @Param("projectName") String projectName,
            @Param("durationMinutes") int durationMinutes,
            @Param("bufferMinutes") int bufferMinutes,
            @Param("quantity") int quantity,
            @Param("unitPriceFen") long unitPriceFen,
            @Param("amountFen") long amountFen,
            @Param("startSlotNo") int startSlotNo,
            @Param("endSlotNo") int endSlotNo,
            @Param("createdAt") LocalDateTime createdAt);

    @Insert("""
            INSERT INTO delayed_job
              (id, job_type, biz_key, payload, run_at, status, retry_count, created_at, updated_at)
            VALUES
              (#{id}, #{jobType}, #{bizKey}, #{payload}, #{runAt}, #{status}, 0, #{createdAt}, #{createdAt})
            """)
    int insertDelayedJob(
            @Param("id") long id,
            @Param("jobType") String jobType,
            @Param("bizKey") String bizKey,
            @Param("payload") String payload,
            @Param("runAt") LocalDateTime runAt,
            @Param("status") String status,
            @Param("createdAt") LocalDateTime createdAt);
}
