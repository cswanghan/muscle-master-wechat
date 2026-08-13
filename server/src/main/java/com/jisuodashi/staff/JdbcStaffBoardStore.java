package com.jisuodashi.staff;

import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
@Profile("!dev")
public class JdbcStaffBoardStore implements StaffBoardStore {

    private static final RowMapper<BookingOrderRef> ORDER = (rs, i) -> new BookingOrderRef(
            rs.getLong("id"),
            rs.getString("order_no"),
            rs.getLong("hold_id"),
            rs.getLong("bed_id"),
            rs.getLong("room_id"),
            rs.getString("status"),
            rs.getObject("lock_expire_at", java.time.LocalDateTime.class),
            rs.getLong("payable_fen"),
            rs.getInt("start_slot_no"),
            rs.getInt("end_slot_no"),
            rs.getInt("buffer_slots"),
            (Long) rs.getObject("add_on_hold_id"),
            rs.getLong("store_id"),
            rs.getObject("service_date", LocalDate.class),
            rs.getLong("customer_id"),
            rs.getLong("therapist_id"));

    private final JdbcTemplate jdbc;

    public JdbcStaffBoardStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<BookingOrderRef> listTherapistDayOrders(long therapistId, LocalDate date) {
        return jdbc.query(
                """
                SELECT id, order_no, hold_id, bed_id, room_id, status, lock_expire_at, payable_fen,
                       start_slot_no, end_slot_no, buffer_slots, add_on_hold_id, store_id,
                       service_date, customer_id, therapist_id
                  FROM booking_order
                 WHERE therapist_id = ? AND service_date = ?
                 ORDER BY start_slot_no, id
                """,
                ORDER,
                therapistId,
                date);
    }

    @Override
    public List<SlotGlance> listTherapistDaySlots(long therapistId, LocalDate date) {
        return jdbc.query(
                """
                SELECT slot_no, status, order_id
                  FROM therapist_slot
                 WHERE therapist_id = ? AND slot_date = ?
                 ORDER BY slot_no
                """,
                (rs, i) -> new SlotGlance(
                        rs.getInt("slot_no"),
                        rs.getString("status"),
                        (Long) rs.getObject("order_id")),
                therapistId,
                date);
    }

    @Override
    public String firstProjectName(long orderId) {
        List<String> names = jdbc.query(
                """
                SELECT project_name
                  FROM order_item
                 WHERE order_id = ? AND item_type = 'PROJECT'
                 ORDER BY id
                 LIMIT 1
                """,
                (rs, i) -> rs.getString(1),
                orderId);
        return names.isEmpty() ? null : names.getFirst();
    }

    @Override
    public RoomBedNames roomBed(long roomId, long bedId) {
        List<RoomBedNames> rows = jdbc.query(
                """
                SELECT r.name AS room_name, b.name AS bed_name
                  FROM bed b
                  JOIN room r ON r.id = b.room_id
                 WHERE b.id = ?
                """,
                (rs, i) -> new RoomBedNames(rs.getString("room_name"), rs.getString("bed_name")),
                bedId);
        if (!rows.isEmpty()) {
            return rows.getFirst();
        }
        String room = jdbc.query(
                "SELECT name FROM room WHERE id = ?",
                (rs, i) -> rs.getString(1),
                roomId).stream().findFirst().orElse("房间");
        return new RoomBedNames(room, "床位");
    }

    @Override
    public long countCompletedForCustomer(long customerId) {
        Long n = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                  FROM booking_order
                 WHERE customer_id = ? AND status IN ('COMPLETED', 'REVIEWED')
                """,
                Long.class,
                customerId);
        return n == null ? 0 : n;
    }
}
