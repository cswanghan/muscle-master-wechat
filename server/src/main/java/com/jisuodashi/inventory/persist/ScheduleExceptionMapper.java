package com.jisuodashi.inventory.persist;

import com.jisuodashi.inventory.ScheduleExceptionStore.ScheduleExceptionRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ScheduleExceptionMapper {

    @Insert("""
            INSERT INTO schedule_exception
              (id, therapist_id, store_id, except_date, type, start_time, end_time, reason,
               status, created_by, created_at, updated_at)
            VALUES
              (#{id}, #{therapistId}, #{storeId}, #{exceptDate}, #{type}, #{startTime}, #{endTime},
               #{reason}, #{status}, #{createdBy}, #{createdAt}, #{updatedAt})
            """)
    int insert(ScheduleExceptionRow row);

    @Select("""
            SELECT id, therapist_id AS therapistId, store_id AS storeId, except_date AS exceptDate,
                   type, start_time AS startTime, end_time AS endTime, reason, status,
                   created_by AS createdBy, created_at AS createdAt, updated_at AS updatedAt
              FROM schedule_exception WHERE id = #{id}
            """)
    ScheduleExceptionRow findById(@Param("id") long id);

    @Select("""
            SELECT id, therapist_id AS therapistId, store_id AS storeId, except_date AS exceptDate,
                   type, start_time AS startTime, end_time AS endTime, reason, status,
                   created_by AS createdBy, created_at AS createdAt, updated_at AS updatedAt
              FROM schedule_exception WHERE id = #{id} FOR UPDATE
            """)
    ScheduleExceptionRow lockById(@Param("id") long id);

    @Update("""
            UPDATE schedule_exception
               SET status = #{nextStatus}, updated_at = #{now}
             WHERE id = #{id} AND status = #{expectedStatus}
            """)
    int casStatus(
            @Param("id") long id,
            @Param("expectedStatus") String expectedStatus,
            @Param("nextStatus") String nextStatus,
            @Param("now") LocalDateTime now);

    @Select("""
            <script>
            SELECT id, therapist_id AS therapistId, store_id AS storeId, except_date AS exceptDate,
                   type, start_time AS startTime, end_time AS endTime, reason, status,
                   created_by AS createdBy, created_at AS createdAt, updated_at AS updatedAt
              FROM schedule_exception
             <where>
               <if test="storeIds != null and storeIds.size() > 0">
                 AND store_id IN
                 <foreach item="s" collection="storeIds" open="(" separator="," close=")">#{s}</foreach>
               </if>
               <if test="from != null">AND except_date &gt;= #{from}</if>
               <if test="to != null">AND except_date &lt;= #{to}</if>
               <if test="status != null">AND status = #{status}</if>
             </where>
             ORDER BY id DESC
             LIMIT 200
            </script>
            """)
    List<ScheduleExceptionRow> list(
            @Param("storeIds") List<Long> storeIds,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("status") String status);
}
