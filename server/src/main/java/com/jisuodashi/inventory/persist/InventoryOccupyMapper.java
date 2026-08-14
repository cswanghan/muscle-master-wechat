package com.jisuodashi.inventory.persist;

import com.jisuodashi.inventory.DelayedJobStore;
import com.jisuodashi.inventory.SlotOccupyStore.BedRef;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import com.jisuodashi.inventory.SlotOccupyStore.IdemRow;
import com.jisuodashi.inventory.SlotOccupyStore.OrderItemInsert;
import com.jisuodashi.inventory.SlotOccupyStore.OwnedSlotRow;
import com.jisuodashi.inventory.SlotOccupyStore.ProjectRef;
import com.jisuodashi.inventory.SlotOccupyStore.SlotHoldMeta;
import com.jisuodashi.inventory.SlotOccupyStore.SlotRow;
import com.jisuodashi.inventory.SlotOccupyStore.OrderChangeLogInsert;
import com.jisuodashi.inventory.SlotOccupyStore.OrderItemInsert;
import com.jisuodashi.inventory.SlotOccupyStore.RescheduleSlotRow;
import com.jisuodashi.inventory.SlotOccupyStore.SlotRow;
import com.jisuodashi.inventory.SlotOccupyStore.TherapistRef;
import org.apache.ibatis.annotations.Delete;
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
                   price_fen AS priceFen, add_on_price_fen AS addOnPriceFen
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
                   start_slot_no AS startSlotNo, end_slot_no AS endSlotNo, buffer_slots AS bufferSlots,
                   add_on_hold_id AS addOnHoldId, store_id AS storeId, service_date AS serviceDate,
                   customer_id AS customerId, therapist_id AS therapistId
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
            SELECT price_fen
              FROM store_project
             WHERE store_id = #{storeId} AND project_id = #{projectId} AND status = 1
            """)
    Long loadStoreProjectPrice(@Param("storeId") long storeId, @Param("projectId") long projectId);

    @Select("""
            SELECT price_override_fen
              FROM therapist_slot
             WHERE therapist_id = #{therapistId} AND slot_date = #{slotDate} AND slot_no = #{slotNo}
            """)
    Long loadSlotPriceOverride(
            @Param("therapistId") long therapistId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNo") int slotNo);

    @Select("""
            SELECT id
              FROM therapist_slot FORCE INDEX (idx_ts_free)
             WHERE therapist_id = #{therapistId} AND slot_date = #{slotDate}
               AND status = 'FREE' AND slot_no IN (${slotNos})
             ORDER BY slot_no
            """)
    List<Long> findFreeTherapistSlotIds(
            @Param("therapistId") long therapistId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNos") String slotNos);

    @Select("""
            SELECT id
              FROM bed_slot FORCE INDEX (idx_bs_free)
             WHERE bed_id = #{bedId} AND slot_date = #{slotDate}
               AND status = 'FREE' AND slot_no IN (${slotNos})
             ORDER BY slot_no
            """)
    List<Long> findFreeBedSlotIds(
            @Param("bedId") long bedId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNos") String slotNos);

    @Select("""
            SELECT slot_no AS slotNo, status
              FROM therapist_slot
             WHERE id IN (${ids}) AND status = 'FREE'
             ORDER BY slot_no
             FOR UPDATE
            """)
    List<SlotRow> lockTherapistSlotsByIds(@Param("ids") String ids);

    @Select("""
            SELECT slot_no AS slotNo, status
              FROM bed_slot
             WHERE id IN (${ids}) AND status = 'FREE'
             ORDER BY slot_no
             FOR UPDATE
            """)
    List<SlotRow> lockBedSlotsByIds(@Param("ids") String ids);

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
               SET status = 'LOCKED',
                   order_id = #{orderId}, hold_id = #{holdId}, lock_expire_at = #{expireAt},
                   updated_at = #{now}
             WHERE therapist_id = #{therapistId} AND slot_date = #{slotDate}
               AND slot_no IN (${slotNos}) AND status = 'FREE'
            """)
    int casLockTherapistSlots(
            @Param("therapistId") long therapistId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNos") String slotNos,
            @Param("orderId") long orderId,
            @Param("holdId") long holdId,
            @Param("expireAt") LocalDateTime expireAt,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE bed_slot
               SET status = 'LOCKED',
                   order_id = #{orderId}, hold_id = #{holdId}, lock_expire_at = #{expireAt},
                   updated_at = #{now}
             WHERE bed_id = #{bedId} AND slot_date = #{slotDate}
               AND slot_no IN (${slotNos}) AND status = 'FREE'
            """)
    int casLockBedSlots(
            @Param("bedId") long bedId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNos") String slotNos,
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

    @Delete("""
            DELETE FROM slot_occupancy
             WHERE hold_id = #{holdId} AND resource_type = 'BED' AND resource_id = #{bedId}
            """)
    int deleteBedOccupancy(@Param("bedId") long bedId, @Param("holdId") long holdId);

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

    @Select("""
            SELECT id, order_no AS orderNo, hold_id AS holdId, bed_id AS bedId, room_id AS roomId,
                   status, lock_expire_at AS lockExpireAt, payable_fen AS payableFen,
                   start_slot_no AS startSlotNo, end_slot_no AS endSlotNo, buffer_slots AS bufferSlots,
                   add_on_hold_id AS addOnHoldId, store_id AS storeId, service_date AS serviceDate,
                   customer_id AS customerId, therapist_id AS therapistId
              FROM booking_order
             WHERE hold_id = #{holdId}
             LIMIT 1
            """)
    BookingOrderRef findOrderByHoldId(@Param("holdId") long holdId);

    @Select("""
            SELECT id, order_no AS orderNo, hold_id AS holdId, bed_id AS bedId, room_id AS roomId,
                   status, lock_expire_at AS lockExpireAt, payable_fen AS payableFen,
                   start_slot_no AS startSlotNo, end_slot_no AS endSlotNo, buffer_slots AS bufferSlots,
                   add_on_hold_id AS addOnHoldId, store_id AS storeId, service_date AS serviceDate,
                   customer_id AS customerId, therapist_id AS therapistId
              FROM booking_order
             WHERE add_on_hold_id = #{holdId}
             LIMIT 1
            """)
    BookingOrderRef findOrderByAddOnHoldId(@Param("holdId") long holdId);

    @Select("""
            SELECT id, order_no AS orderNo, hold_id AS holdId, bed_id AS bedId, room_id AS roomId,
                   status, lock_expire_at AS lockExpireAt, payable_fen AS payableFen,
                   start_slot_no AS startSlotNo, end_slot_no AS endSlotNo, buffer_slots AS bufferSlots,
                   add_on_hold_id AS addOnHoldId, store_id AS storeId, service_date AS serviceDate,
                   customer_id AS customerId, therapist_id AS therapistId
              FROM booking_order
             WHERE hold_id = #{holdId}
             LIMIT 1
             FOR UPDATE
            """)
    BookingOrderRef lockOrderByHoldId(@Param("holdId") long holdId);

    @Select("""
            SELECT id, order_no AS orderNo, hold_id AS holdId, bed_id AS bedId, room_id AS roomId,
                   status, lock_expire_at AS lockExpireAt, payable_fen AS payableFen,
                   start_slot_no AS startSlotNo, end_slot_no AS endSlotNo, buffer_slots AS bufferSlots,
                   add_on_hold_id AS addOnHoldId, store_id AS storeId, service_date AS serviceDate,
                   customer_id AS customerId, therapist_id AS therapistId
              FROM booking_order
             WHERE id = #{id}
             FOR UPDATE
            """)
    BookingOrderRef lockOrderById(@Param("id") long id);

    @Select("""
            SELECT id, order_no AS orderNo, hold_id AS holdId, bed_id AS bedId, room_id AS roomId,
                   status, lock_expire_at AS lockExpireAt, payable_fen AS payableFen,
                   start_slot_no AS startSlotNo, end_slot_no AS endSlotNo, buffer_slots AS bufferSlots,
                   add_on_hold_id AS addOnHoldId, store_id AS storeId, service_date AS serviceDate,
                   customer_id AS customerId, therapist_id AS therapistId
              FROM booking_order
             WHERE id = #{id}
            """)
    BookingOrderRef findOrderById(@Param("id") long id);

    @Select("""
            SELECT id, order_no AS orderNo, hold_id AS holdId, bed_id AS bedId, room_id AS roomId,
                   status, lock_expire_at AS lockExpireAt, payable_fen AS payableFen,
                   start_slot_no AS startSlotNo, end_slot_no AS endSlotNo, buffer_slots AS bufferSlots,
                   add_on_hold_id AS addOnHoldId, store_id AS storeId, service_date AS serviceDate,
                   customer_id AS customerId, therapist_id AS therapistId
              FROM booking_order
             WHERE customer_id = #{customerId}
             ORDER BY id DESC
            """)
    List<BookingOrderRef> listOrdersByCustomer(@Param("customerId") long customerId);

    @Select("""
            SELECT id, order_no AS orderNo, hold_id AS holdId, bed_id AS bedId, room_id AS roomId,
                   status, lock_expire_at AS lockExpireAt, payable_fen AS payableFen,
                   start_slot_no AS startSlotNo, end_slot_no AS endSlotNo, buffer_slots AS bufferSlots,
                   add_on_hold_id AS addOnHoldId, store_id AS storeId, service_date AS serviceDate,
                   customer_id AS customerId, therapist_id AS therapistId
              FROM booking_order
             WHERE order_no = #{orderNo}
             LIMIT 1
            """)
    BookingOrderRef findOrderByOrderNo(@Param("orderNo") String orderNo);

    @Select("""
            SELECT id, order_no AS orderNo, hold_id AS holdId, bed_id AS bedId, room_id AS roomId,
                   status, lock_expire_at AS lockExpireAt, payable_fen AS payableFen,
                   start_slot_no AS startSlotNo, end_slot_no AS endSlotNo, buffer_slots AS bufferSlots,
                   add_on_hold_id AS addOnHoldId, store_id AS storeId, service_date AS serviceDate,
                   customer_id AS customerId, therapist_id AS therapistId
              FROM booking_order
             WHERE customer_id = #{customerId}
             ORDER BY service_date DESC, start_slot_no ASC
            """)
    List<BookingOrderRef> listOrdersByCustomerId(@Param("customerId") long customerId);

    @Delete("""
            DELETE o FROM slot_occupancy o
             INNER JOIN therapist_slot ts
                ON o.resource_type = 'THERAPIST' AND o.resource_id = ts.therapist_id
               AND o.slot_date = ts.slot_date AND o.slot_no = ts.slot_no
             WHERE o.hold_id = #{holdId} AND ts.hold_id = #{holdId} AND ts.status = 'LOCKED'
            """)
    int deleteOccupancyForLockedTherapist(@Param("holdId") long holdId);

    @Delete("""
            DELETE o FROM slot_occupancy o
             INNER JOIN bed_slot bs
                ON o.resource_type = 'BED' AND o.resource_id = bs.bed_id
               AND o.slot_date = bs.slot_date AND o.slot_no = bs.slot_no
             WHERE o.hold_id = #{holdId} AND bs.hold_id = #{holdId} AND bs.status = 'LOCKED'
            """)
    int deleteOccupancyForLockedBed(@Param("holdId") long holdId);

    @Delete("""
            DELETE FROM slot_occupancy WHERE hold_id = #{holdId}
            """)
    int deleteOccupancyByHold(@Param("holdId") long holdId);

    @Update("""
            UPDATE therapist_slot
               SET status = 'FREE', order_id = NULL, hold_id = NULL, lock_expire_at = NULL,
                   updated_at = #{now}
             WHERE hold_id = #{holdId} AND status = 'LOCKED'
            """)
    int freeLockedTherapistSlots(@Param("holdId") long holdId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE bed_slot
               SET status = 'FREE', order_id = NULL, hold_id = NULL, lock_expire_at = NULL,
                   updated_at = #{now}
             WHERE hold_id = #{holdId} AND status = 'LOCKED'
            """)
    int freeLockedBedSlots(@Param("holdId") long holdId, @Param("now") LocalDateTime now);

    @Select("""
            SELECT hold_id FROM (
                SELECT hold_id FROM therapist_slot
                 WHERE status = 'LOCKED' AND lock_expire_at < #{now} AND hold_id IS NOT NULL
                UNION
                SELECT hold_id FROM bed_slot
                 WHERE status = 'LOCKED' AND lock_expire_at < #{now} AND hold_id IS NOT NULL
            ) u
             ORDER BY hold_id
             LIMIT #{limit}
            """)
    List<Long> findExpiredLockedHoldIds(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Select("""
            SELECT
              (SELECT COUNT(*) FROM therapist_slot
                WHERE status = 'LOCKED' AND lock_expire_at < #{cutoff})
              +
              (SELECT COUNT(*) FROM bed_slot
                WHERE status = 'LOCKED' AND lock_expire_at < #{cutoff})
            """)
    int countLockedExpiredBefore(@Param("cutoff") LocalDateTime cutoff);

    @Update("""
            UPDATE therapist_slot
               SET status = CASE WHEN slot_no < #{serviceEnd} THEN 'BOOKED' ELSE 'BUFFER' END,
                   lock_expire_at = NULL,
                   updated_at = #{now}
             WHERE order_id = #{orderId} AND hold_id = #{holdId} AND status = 'LOCKED'
            """)
    int confirmPaidTherapistSlots(
            @Param("orderId") long orderId,
            @Param("holdId") long holdId,
            @Param("serviceEnd") int serviceEnd,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE bed_slot
               SET status = CASE WHEN slot_no < #{serviceEnd} THEN 'BOOKED' ELSE 'BUFFER' END,
                   lock_expire_at = NULL,
                   updated_at = #{now}
             WHERE order_id = #{orderId} AND hold_id = #{holdId} AND status = 'LOCKED'
            """)
    int confirmPaidBedSlots(
            @Param("orderId") long orderId,
            @Param("holdId") long holdId,
            @Param("serviceEnd") int serviceEnd,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE delayed_job
               SET status = 'DONE', updated_at = #{now}
             WHERE job_type = 'RELEASE_LOCK' AND biz_key = #{bizKey}
               AND status IN ('PENDING', 'RUNNING')
            """)
    int markReleaseLockJobDone(@Param("bizKey") String bizKey, @Param("now") LocalDateTime now);

    @Select("""
            SELECT store_id AS storeId, slot_date AS slotDate
              FROM therapist_slot
             WHERE hold_id = #{holdId}
             LIMIT 1
            """)
    SlotHoldMeta findTherapistHoldMeta(@Param("holdId") long holdId);

    @Select("""
            SELECT store_id AS storeId, slot_date AS slotDate
              FROM bed_slot
             WHERE hold_id = #{holdId}
             LIMIT 1
            """)
    SlotHoldMeta findBedHoldMeta(@Param("holdId") long holdId);

    @Select("""
            SELECT id FROM delayed_job
             WHERE (status = 'PENDING' AND run_at <= #{now})
                OR (status = 'RUNNING' AND lease_until < #{now})
             ORDER BY run_at
             LIMIT #{limit}
             FOR UPDATE SKIP LOCKED
            """)
    List<Long> findClaimableJobIds(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("""
            UPDATE delayed_job
               SET status = 'RUNNING',
                   locked_by = #{instanceId},
                   locked_at = #{now},
                   lease_until = #{leaseUntil},
                   retry_count = retry_count + IF(status = 'RUNNING', 1, 0),
                   updated_at = #{now}
             WHERE id IN (${ids})
            """)
    int markJobsRunning(
            @Param("ids") String ids,
            @Param("instanceId") String instanceId,
            @Param("now") LocalDateTime now,
            @Param("leaseUntil") LocalDateTime leaseUntil);

    @Select("""
            SELECT id, job_type AS jobType, biz_key AS bizKey, payload, run_at AS runAt,
                   status, locked_by AS lockedBy, lease_until AS leaseUntil,
                   retry_count AS retryCount, last_error AS lastError
              FROM delayed_job
             WHERE id = #{id}
            """)
    DelayedJobStore.DelayedJobRow findJob(@Param("id") long id);

    @Update("""
            UPDATE delayed_job
               SET status = #{status}, last_error = #{lastError}, updated_at = #{now}
             WHERE id = #{id}
            """)
    int completeJob(
            @Param("id") long id,
            @Param("status") String status,
            @Param("lastError") String lastError,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE booking_order
               SET status = #{toStatus},
                   version = version + 1,
                   updated_at = #{now},
                   paid_at = CASE WHEN #{toStatus} = 'BOOKED' AND paid_at IS NULL THEN #{now} ELSE paid_at END,
                   checked_in_at = CASE WHEN #{toStatus} = 'CHECKED_IN' AND checked_in_at IS NULL THEN #{now} ELSE checked_in_at END,
                   service_started_at = CASE WHEN #{toStatus} = 'IN_SERVICE' AND service_started_at IS NULL THEN #{now} ELSE service_started_at END,
                   service_ended_at = CASE WHEN #{toStatus} IN ('COMPLETED','REVIEWED') AND service_ended_at IS NULL THEN #{now} ELSE service_ended_at END
             WHERE id = #{orderId} AND status = #{expectedStatus}
            """)
    int casOrderStatus(
            @Param("orderId") long orderId,
            @Param("expectedStatus") String expectedStatus,
            @Param("toStatus") String toStatus,
            @Param("now") LocalDateTime now);

    @Delete("""
            DELETE FROM slot_occupancy
             WHERE order_id = #{orderId} AND slot_no >= #{fromSlotNo}
            """)
    int deleteOccupancyFromSlot(@Param("orderId") long orderId, @Param("fromSlotNo") int fromSlotNo);

    @Update("""
            UPDATE therapist_slot
               SET status = 'FREE', order_id = NULL, hold_id = NULL, lock_expire_at = NULL,
                   updated_at = #{now}
             WHERE order_id = #{orderId} AND slot_no >= #{fromSlotNo}
               AND status IN ('LOCKED','BOOKED','BUFFER')
            """)
    int freeOrderTherapistSlotsFrom(
            @Param("orderId") long orderId,
            @Param("fromSlotNo") int fromSlotNo,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE bed_slot
               SET status = 'FREE', order_id = NULL, hold_id = NULL, lock_expire_at = NULL,
                   updated_at = #{now}
             WHERE order_id = #{orderId} AND slot_no >= #{fromSlotNo}
               AND status IN ('LOCKED','BOOKED','BUFFER')
            """)
    int freeOrderBedSlotsFrom(
            @Param("orderId") long orderId,
            @Param("fromSlotNo") int fromSlotNo,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE booking_order
               SET add_on_hold_id = NULL, updated_at = #{now}
             WHERE id = #{orderId}
            """)
    int clearAddOnHold(@Param("orderId") long orderId, @Param("now") LocalDateTime now);

    @Delete("""
            DELETE FROM slot_occupancy
             WHERE hold_id = #{holdId} AND slot_no >= #{fromSlotNo}
            """)
    int deleteOccupancyForHoldFromSlot(@Param("holdId") long holdId, @Param("fromSlotNo") int fromSlotNo);

    @Update("""
            UPDATE therapist_slot
               SET status = 'FREE', order_id = NULL, hold_id = NULL, lock_expire_at = NULL,
                   updated_at = #{now}
             WHERE hold_id = #{holdId} AND slot_no >= #{fromSlotNo}
            """)
    int freeHoldTherapistSlotsFrom(
            @Param("holdId") long holdId,
            @Param("fromSlotNo") int fromSlotNo,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE bed_slot
               SET status = 'FREE', order_id = NULL, hold_id = NULL, lock_expire_at = NULL,
                   updated_at = #{now}
             WHERE hold_id = #{holdId} AND slot_no >= #{fromSlotNo}
            """)
    int freeHoldBedSlotsFrom(
            @Param("holdId") long holdId,
            @Param("fromSlotNo") int fromSlotNo,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE therapist_slot
               SET status = 'BUFFER', hold_id = #{mainHoldId}, lock_expire_at = NULL,
                   updated_at = #{now}
             WHERE order_id = #{orderId} AND slot_no >= #{fromSlotNo} AND slot_no < #{toExclusive}
            """)
    int restoreTherapistBufferSlots(
            @Param("orderId") long orderId,
            @Param("fromSlotNo") int fromSlotNo,
            @Param("toExclusive") int toExclusive,
            @Param("mainHoldId") long mainHoldId,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE bed_slot
               SET status = 'BUFFER', hold_id = #{mainHoldId}, lock_expire_at = NULL,
                   updated_at = #{now}
             WHERE order_id = #{orderId} AND slot_no >= #{fromSlotNo} AND slot_no < #{toExclusive}
            """)
    int restoreBedBufferSlots(
            @Param("orderId") long orderId,
            @Param("fromSlotNo") int fromSlotNo,
            @Param("toExclusive") int toExclusive,
            @Param("mainHoldId") long mainHoldId,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE slot_occupancy
               SET hold_id = #{mainHoldId}
             WHERE order_id = #{orderId} AND slot_no >= #{fromSlotNo} AND slot_no < #{toExclusive}
            """)
    int reassignOccupancyHold(
            @Param("orderId") long orderId,
            @Param("fromSlotNo") int fromSlotNo,
            @Param("toExclusive") int toExclusive,
            @Param("mainHoldId") long mainHoldId);

    @Delete("""
            DELETE FROM order_item
             WHERE order_id = #{orderId} AND item_type = 'ADD_ON'
               AND start_slot_no >= #{fromSlotNo}
            """)
    int deleteUnpaidAddOnItems(@Param("orderId") long orderId, @Param("fromSlotNo") int fromSlotNo);

    @Select("""
            SELECT id
              FROM therapist_slot
             WHERE therapist_id = #{therapistId} AND slot_date = #{slotDate}
               AND slot_no IN (${slotNos})
             ORDER BY slot_no
            """)
    List<Long> findTherapistSlotIds(
            @Param("therapistId") long therapistId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNos") String slotNos);

    @Select("""
            SELECT id
              FROM bed_slot
             WHERE bed_id = #{bedId} AND slot_date = #{slotDate}
               AND slot_no IN (${slotNos})
             ORDER BY slot_no
            """)
    List<Long> findBedSlotIds(
            @Param("bedId") long bedId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNos") String slotNos);

    @Select("""
            SELECT slot_no AS slotNo, status, order_id AS orderId
              FROM therapist_slot
             WHERE id IN (${ids})
             ORDER BY slot_no
             FOR UPDATE
            """)
    List<OwnedSlotRow> lockOwnedTherapistSlotsByIds(@Param("ids") String ids);

    @Select("""
            SELECT slot_no AS slotNo, status, order_id AS orderId
              FROM bed_slot
             WHERE id IN (${ids})
             ORDER BY slot_no
             FOR UPDATE
            """)
    List<OwnedSlotRow> lockOwnedBedSlotsByIds(@Param("ids") String ids);

    @Update("""
            UPDATE therapist_slot
               SET status = #{destStatus}, order_id = #{orderId}, hold_id = #{holdId},
                   lock_expire_at = #{expireAt}, updated_at = #{now}
             WHERE therapist_id = #{therapistId} AND slot_date = #{slotDate} AND slot_no = #{slotNo}
               AND status = #{expectedStatus}
               AND ((#{expectedOrderId} IS NULL AND order_id IS NULL)
                    OR order_id = #{expectedOrderId})
            """)
    int updateTherapistSlotDest(
            @Param("therapistId") long therapistId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNo") int slotNo,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedOrderId") Long expectedOrderId,
            @Param("destStatus") String destStatus,
            @Param("orderId") long orderId,
            @Param("holdId") long holdId,
            @Param("expireAt") LocalDateTime expireAt,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE bed_slot
               SET status = #{destStatus}, order_id = #{orderId}, hold_id = #{holdId},
                   lock_expire_at = #{expireAt}, updated_at = #{now}
             WHERE bed_id = #{bedId} AND slot_date = #{slotDate} AND slot_no = #{slotNo}
               AND status = #{expectedStatus}
               AND ((#{expectedOrderId} IS NULL AND order_id IS NULL)
                    OR order_id = #{expectedOrderId})
            """)
    int updateBedSlotDest(
            @Param("bedId") long bedId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNo") int slotNo,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedOrderId") Long expectedOrderId,
            @Param("destStatus") String destStatus,
            @Param("orderId") long orderId,
            @Param("holdId") long holdId,
            @Param("expireAt") LocalDateTime expireAt,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE booking_order
               SET end_slot_no = #{newEndSlotNo},
                   payable_fen = payable_fen + #{addAmountFen},
                   paid_fen = paid_fen + #{addAmountFen},
                   updated_at = #{now}
             WHERE id = #{orderId}
            """)
    int applyCashAddOn(
            @Param("orderId") long orderId,
            @Param("newEndSlotNo") int newEndSlotNo,
            @Param("addAmountFen") long addAmountFen,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE booking_order
               SET add_on_hold_id = #{addHoldId}, updated_at = #{now}
             WHERE id = #{orderId}
            """)
    int setAddOnHold(
            @Param("orderId") long orderId,
            @Param("addHoldId") long addHoldId,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE booking_order
               SET end_slot_no = #{newEndSlotNo},
                   add_on_hold_id = NULL,
                   payable_fen = payable_fen + #{addAmountFen},
                   paid_fen = paid_fen + #{addAmountFen},
                   updated_at = #{now}
             WHERE id = #{orderId}
            """)
    int applyPaidAddOn(
            @Param("orderId") long orderId,
            @Param("newEndSlotNo") int newEndSlotNo,
            @Param("addAmountFen") long addAmountFen,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE delayed_job
               SET status = 'DONE', updated_at = #{now}
             WHERE job_type = 'RELEASE_ADDON' AND biz_key = #{bizKey}
               AND status IN ('PENDING', 'RUNNING')
            """)
    int markReleaseAddonJobDone(@Param("bizKey") String bizKey, @Param("now") LocalDateTime now);

    @Select("""
            SELECT id, order_id AS orderId, item_type AS itemType, project_id AS projectId,
                   project_name AS projectName, duration_minutes AS durationMinutes,
                   buffer_minutes AS bufferMinutes, quantity,
                   unit_price_fen AS unitPriceFen, amount_fen AS amountFen,
                   start_slot_no AS startSlotNo, end_slot_no AS endSlotNo,
                   created_at AS createdAt
              FROM order_item
             WHERE order_id = #{orderId} AND item_type = 'ADD_ON'
             ORDER BY id DESC
             LIMIT 1
            """)
    OrderItemInsert findLatestAddOnItem(@Param("orderId") long orderId);

    @Insert("""
            INSERT INTO payment
              (id, payment_no, order_id, channel, amount_fen, status, wx_prepay_id,
               wx_transaction_id, paid_at, notify_raw, created_at, updated_at)
            VALUES
              (#{id}, #{paymentNo}, #{orderId}, 'CASH', #{amountFen}, 'SUCCESS', NULL,
               #{wxTxn}, #{now}, NULL, #{now}, #{now})
            """)
    int insertCashPayment(
            @Param("id") long id,
            @Param("paymentNo") String paymentNo,
            @Param("orderId") long orderId,
            @Param("amountFen") long amountFen,
            @Param("wxTxn") String wxTxn,
            @Param("now") LocalDateTime now);

    @Select("""
            SELECT slot_no AS slotNo, status
              FROM therapist_slot
             WHERE therapist_id = #{therapistId} AND slot_date = #{slotDate}
               AND slot_no IN (${slotNos})
             ORDER BY slot_no
            """)
    List<SlotRow> listTherapistSlots(
            @Param("therapistId") long therapistId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNos") String slotNos);

    @Update("""
            UPDATE therapist_slot
               SET status = #{status},
                   order_id = #{orderId}, hold_id = #{holdId},
                   lock_expire_at = #{expireAt}, updated_at = #{now}
             WHERE therapist_id = #{therapistId} AND slot_date = #{slotDate}
               AND slot_no = #{slotNo} AND status = 'FREE'
            """)
    int assignTherapistSlot(
            @Param("therapistId") long therapistId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNo") int slotNo,
            @Param("status") String status,
            @Param("orderId") long orderId,
            @Param("holdId") long holdId,
            @Param("expireAt") LocalDateTime expireAt,
            @Param("now") LocalDateTime now);

    @Delete("""
            DELETE FROM slot_occupancy
             WHERE resource_type = 'THERAPIST' AND resource_id = #{therapistId}
               AND slot_date = #{slotDate} AND slot_no IN (${slotNos})
            """)
    int deleteTherapistOccupancy(
            @Param("therapistId") long therapistId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNos") String slotNos);

    @Update("""
            UPDATE therapist_slot
               SET status = 'FREE', order_id = NULL, hold_id = NULL, lock_expire_at = NULL,
                   updated_at = #{now}
             WHERE therapist_id = #{therapistId} AND slot_date = #{slotDate}
               AND slot_no IN (${slotNos})
            """)
    int freeTherapistSlots(
            @Param("therapistId") long therapistId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNos") String slotNos,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE booking_order
               SET therapist_id = #{therapistId},
                   therapist_home_store_id = #{homeStoreId},
                   updated_at = #{now}
             WHERE id = #{orderId}
            """)
    int updateTherapist(
            @Param("orderId") long orderId,
            @Param("therapistId") long therapistId,
            @Param("homeStoreId") long homeStoreId,
            @Param("now") LocalDateTime now);


    @Select("""
            SELECT status
              FROM therapist_slot
             WHERE therapist_id = #{resourceId} AND slot_date = #{slotDate} AND slot_no = #{slotNo}
            """)
    String peekTherapistSlotStatus(
            @Param("resourceId") long resourceId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNo") int slotNo);

    @Select("""
            SELECT status
              FROM bed_slot
             WHERE bed_id = #{resourceId} AND slot_date = #{slotDate} AND slot_no = #{slotNo}
            """)
    String peekBedSlotStatus(
            @Param("resourceId") long resourceId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNo") int slotNo);

    @Select("""
            SELECT 'THERAPIST' AS resourceType, therapist_id AS resourceId, slot_date AS slotDate,
                   slot_no AS slotNo, status, order_id AS orderId, store_id AS storeId
              FROM therapist_slot
             WHERE therapist_id = #{resourceId} AND slot_date = #{slotDate} AND slot_no = #{slotNo}
             FOR UPDATE
            """)
    RescheduleSlotRow lockTherapistSlotRow(
            @Param("resourceId") long resourceId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNo") int slotNo);

    @Select("""
            SELECT 'BED' AS resourceType, bed_id AS resourceId, slot_date AS slotDate,
                   slot_no AS slotNo, status, order_id AS orderId, store_id AS storeId
              FROM bed_slot
             WHERE bed_id = #{resourceId} AND slot_date = #{slotDate} AND slot_no = #{slotNo}
             FOR UPDATE
            """)
    RescheduleSlotRow lockBedSlotRow(
            @Param("resourceId") long resourceId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNo") int slotNo);

    @Update("""
            UPDATE therapist_slot
               SET status = #{status}, order_id = #{orderId}, hold_id = #{holdId},
                   lock_expire_at = NULL, updated_at = #{now}
             WHERE therapist_id = #{resourceId} AND slot_date = #{slotDate} AND slot_no = #{slotNo}
               AND status = 'FREE'
            """)
    int applyTherapistAcquire(
            @Param("resourceId") long resourceId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNo") int slotNo,
            @Param("status") String status,
            @Param("orderId") long orderId,
            @Param("holdId") long holdId,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE bed_slot
               SET status = #{status}, order_id = #{orderId}, hold_id = #{holdId},
                   lock_expire_at = NULL, updated_at = #{now}
             WHERE bed_id = #{resourceId} AND slot_date = #{slotDate} AND slot_no = #{slotNo}
               AND status = 'FREE'
            """)
    int applyBedAcquire(
            @Param("resourceId") long resourceId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNo") int slotNo,
            @Param("status") String status,
            @Param("orderId") long orderId,
            @Param("holdId") long holdId,
            @Param("now") LocalDateTime now);

    @Delete("""
            DELETE FROM slot_occupancy
             WHERE resource_type = #{resourceType} AND resource_id = #{resourceId}
               AND slot_date = #{slotDate} AND slot_no = #{slotNo} AND order_id = #{orderId}
            """)
    int deleteOccupancyKey(
            @Param("resourceType") String resourceType,
            @Param("resourceId") long resourceId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNo") int slotNo,
            @Param("orderId") long orderId);

    @Update("""
            UPDATE therapist_slot
               SET status = 'FREE', order_id = NULL, hold_id = NULL, lock_expire_at = NULL,
                   updated_at = #{now}
             WHERE therapist_id = #{resourceId} AND slot_date = #{slotDate} AND slot_no = #{slotNo}
               AND order_id = #{orderId}
            """)
    int freeTherapistSlotKey(
            @Param("resourceId") long resourceId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNo") int slotNo,
            @Param("orderId") long orderId,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE bed_slot
               SET status = 'FREE', order_id = NULL, hold_id = NULL, lock_expire_at = NULL,
                   updated_at = #{now}
             WHERE bed_id = #{resourceId} AND slot_date = #{slotDate} AND slot_no = #{slotNo}
               AND order_id = #{orderId}
            """)
    int freeBedSlotKey(
            @Param("resourceId") long resourceId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNo") int slotNo,
            @Param("orderId") long orderId,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE therapist_slot
               SET hold_id = #{holdId}, status = #{status}, updated_at = #{now}
             WHERE therapist_id = #{resourceId} AND slot_date = #{slotDate} AND slot_no = #{slotNo}
               AND order_id = #{orderId}
            """)
    int reholdTherapistSlot(
            @Param("resourceId") long resourceId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNo") int slotNo,
            @Param("orderId") long orderId,
            @Param("holdId") long holdId,
            @Param("status") String status,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE bed_slot
               SET hold_id = #{holdId}, status = #{status}, updated_at = #{now}
             WHERE bed_id = #{resourceId} AND slot_date = #{slotDate} AND slot_no = #{slotNo}
               AND order_id = #{orderId}
            """)
    int reholdBedSlot(
            @Param("resourceId") long resourceId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNo") int slotNo,
            @Param("orderId") long orderId,
            @Param("holdId") long holdId,
            @Param("status") String status,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE slot_occupancy
               SET hold_id = #{holdId}
             WHERE resource_type = #{resourceType} AND resource_id = #{resourceId}
               AND slot_date = #{slotDate} AND slot_no = #{slotNo} AND order_id = #{orderId}
            """)
    int reholdOccupancyKey(
            @Param("resourceType") String resourceType,
            @Param("resourceId") long resourceId,
            @Param("slotDate") LocalDate slotDate,
            @Param("slotNo") int slotNo,
            @Param("orderId") long orderId,
            @Param("holdId") long holdId);

    @Update("""
            UPDATE booking_order
               SET hold_id = #{holdId},
                   therapist_id = #{therapistId},
                   therapist_home_store_id = COALESCE(#{therapistHomeStoreId}, therapist_home_store_id),
                   service_date = #{serviceDate},
                   start_slot_no = #{startSlotNo},
                   end_slot_no = #{endSlotNo},
                   bed_id = #{bedId},
                   room_id = #{roomId},
                   updated_at = #{now}
             WHERE id = #{orderId}
            """)
    int updateOrderForReschedule(
            @Param("orderId") long orderId,
            @Param("holdId") long holdId,
            @Param("therapistId") long therapistId,
            @Param("therapistHomeStoreId") Long therapistHomeStoreId,
            @Param("serviceDate") LocalDate serviceDate,
            @Param("startSlotNo") int startSlotNo,
            @Param("endSlotNo") int endSlotNo,
            @Param("bedId") long bedId,
            @Param("roomId") long roomId,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE order_item
               SET start_slot_no = #{startSlotNo}, end_slot_no = #{endSlotNo}
             WHERE order_id = #{orderId} AND item_type = 'PROJECT'
            """)
    int updateProjectItemWindow(
            @Param("orderId") long orderId,
            @Param("startSlotNo") int startSlotNo,
            @Param("endSlotNo") int endSlotNo);

    @Insert("""
            INSERT INTO order_change_log
              (id, order_id, change_type, before_json, after_json, operator_id, created_at)
            VALUES
              (#{id}, #{orderId}, #{changeType}, #{beforeJson}, #{afterJson}, #{operatorId}, #{createdAt})
            """)
    int insertOrderChangeLog(
            @Param("id") long id,
            @Param("orderId") long orderId,
            @Param("changeType") String changeType,
            @Param("beforeJson") String beforeJson,
            @Param("afterJson") String afterJson,
            @Param("operatorId") Long operatorId,
            @Param("createdAt") LocalDateTime createdAt);

    @Select("""
            SELECT id, order_id AS orderId, item_type AS itemType, project_id AS projectId,
                   project_name AS projectName, duration_minutes AS durationMinutes,
                   buffer_minutes AS bufferMinutes, quantity, unit_price_fen AS unitPriceFen,
                   amount_fen AS amountFen, start_slot_no AS startSlotNo, end_slot_no AS endSlotNo,
                   created_at AS createdAt
              FROM order_item
             WHERE order_id = #{orderId} AND item_type = 'PROJECT'
             LIMIT 1
            """)
    OrderItemInsert findProjectItem(@Param("orderId") long orderId);

    @Select("""
            SELECT slot_no AS slotNo, status
              FROM therapist_slot
             WHERE store_id = #{storeId} AND slot_date = #{date}
             ORDER BY slot_no
            """)
    List<SlotRow> listTherapistSlotsByStore(@Param("storeId") long storeId, @Param("date") LocalDate date);

    @Select("""
            SELECT COUNT(*)
              FROM therapist_slot
             WHERE therapist_id = #{therapistId} AND slot_date = #{date}
               AND slot_no >= #{fromSlotNo} AND slot_no < #{toSlotNo}
               AND status IN ('LOCKED','BOOKED','BUFFER')
            """)
    int countBusyTherapistSlots(
            @Param("therapistId") long therapistId,
            @Param("date") LocalDate date,
            @Param("fromSlotNo") int fromSlotNo,
            @Param("toSlotNo") int toSlotNoExclusive);

    @Update("""
            UPDATE therapist_slot
               SET status = 'REST', updated_at = #{now}
             WHERE therapist_id = #{therapistId} AND slot_date = #{date}
               AND slot_no >= #{fromSlotNo} AND slot_no < #{toSlotNo}
               AND status = 'FREE'
            """)
    int restFreeTherapistSlots(
            @Param("therapistId") long therapistId,
            @Param("date") LocalDate date,
            @Param("fromSlotNo") int fromSlotNo,
            @Param("toSlotNo") int toSlotNoExclusive,
            @Param("now") LocalDateTime now);

    @Select("""
            SELECT
              (SELECT COUNT(*) FROM therapist_slot ts
                LEFT JOIN slot_occupancy o
                  ON o.resource_type = 'THERAPIST'
                 AND o.resource_id = ts.therapist_id
                 AND o.slot_date = ts.slot_date
                 AND o.slot_no = ts.slot_no
               WHERE (o.id IS NOT NULL) <> (ts.status IN ('LOCKED','BOOKED','BUFFER')))
              +
              (SELECT COUNT(*) FROM bed_slot bs
                LEFT JOIN slot_occupancy o
                  ON o.resource_type = 'BED'
                 AND o.resource_id = bs.bed_id
                 AND o.slot_date = bs.slot_date
                 AND o.slot_no = bs.slot_no
               WHERE (o.id IS NOT NULL) <> (bs.status IN ('LOCKED','BOOKED','BUFFER')))
              +
              (SELECT COUNT(*) FROM slot_occupancy o
                WHERE (o.resource_type = 'THERAPIST' AND NOT EXISTS (
                         SELECT 1 FROM therapist_slot ts
                          WHERE ts.therapist_id = o.resource_id
                            AND ts.slot_date = o.slot_date
                            AND ts.slot_no = o.slot_no))
                   OR (o.resource_type = 'BED' AND NOT EXISTS (
                         SELECT 1 FROM bed_slot bs
                          WHERE bs.bed_id = o.resource_id
                            AND bs.slot_date = o.slot_date
                            AND bs.slot_no = o.slot_no)))
            """)
    int countInventoryDrift();

    @Select("""
            SELECT COUNT(*) FROM booking_order
             WHERE store_id = #{storeId} AND created_at >= #{since}
            """)
    int countOrdersCreatedSince(@Param("storeId") long storeId, @Param("since") LocalDateTime since);
}
