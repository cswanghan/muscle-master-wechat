package com.jisuodashi.admin;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
@Profile("dev")
public class InMemoryAdminOrderStore implements AdminOrderStore {

    private static final LocalDate SERVICE = LocalDate.of(2026, 8, 14);

    private final CopyOnWriteArrayList<AdminOrderRow> orders = new CopyOnWriteArrayList<>();

    public InMemoryAdminOrderStore() {
        seed();
    }

    private void seed() {
        orders.clear();
        orders.add(row(AdminDemoIds.ORDER_ABNORMAL, "A20260814001", AdminDemoIds.STORE,
                "ABNORMAL", LocalDateTime.of(2026, 8, 14, 9, 0, 0), 19800, false));
        orders.add(row(AdminDemoIds.ORDER_MANUAL, "A20260814002", AdminDemoIds.STORE,
                "BOOKED", LocalDateTime.of(2026, 8, 14, 10, 0, 0), 12800, true));
        orders.add(row(AdminDemoIds.ORDER_BOOKED, "A20260814003", AdminDemoIds.STORE,
                "BOOKED", LocalDateTime.of(2026, 8, 14, 11, 0, 0), 19800, false));
        orders.add(row(AdminDemoIds.ORDER_COMPLETED, "A20260814004", AdminDemoIds.STORE,
                "COMPLETED", LocalDateTime.of(2026, 8, 14, 8, 0, 0), 26800, false));
        orders.add(row(AdminDemoIds.ORDER_PENDING, "A20260814005", AdminDemoIds.STORE,
                "PENDING_PAY", LocalDateTime.of(2026, 8, 14, 12, 0, 0), 19800, false));
        orders.add(row(AdminDemoIds.ORDER_EAST, "A20260814006", AdminDemoIds.STORE_EAST,
                "BOOKED", LocalDateTime.of(2026, 8, 14, 13, 0, 0), 12800, false));
        orders.add(row(AdminDemoIds.ORDER_SAME_SEC_HI, "A20260814007", AdminDemoIds.STORE,
                "COMPLETED", LocalDateTime.of(2026, 8, 14, 14, 0, 0, 500_000_000), 8800, false));
        orders.add(row(AdminDemoIds.ORDER_SAME_SEC_LO, "A20260814008", AdminDemoIds.STORE,
                "COMPLETED", LocalDateTime.of(2026, 8, 14, 14, 0, 0, 200_000_000), 8800, false));
    }

    private static AdminOrderRow row(
            long id, String orderNo, long storeId, String status, LocalDateTime createdAt,
            long payableFen, boolean manual) {
        return new AdminOrderRow(
                id, orderNo, storeId, AdminDemoIds.THERAPIST_LIN, status, SERVICE, createdAt, payableFen, manual);
    }

    @Override
    public List<AdminOrderRow> list() {
        return List.copyOf(orders);
    }

    @Override
    public void resetDemo() {
        seed();
    }
}
