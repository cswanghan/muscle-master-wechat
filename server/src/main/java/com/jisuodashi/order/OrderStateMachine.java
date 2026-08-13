package com.jisuodashi.order;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.inventory.SlotOccupyStore;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Closed, table-driven order state machine (D8 / §3).
 * Job/API only {@link #fire}; {@code Release*} must not call back (D25 Law A).
 * Status writes go through {@link SlotOccupyStore#casOrderStatus} here — no setStatus.
 */
@Service
public class OrderStateMachine {

    private static final Map<Key, OrderTransition> TABLE = buildTable();

    private final SlotOccupyStore store;
    private final SlotOccupyService occupy;
    private final AppClock clock;
    private final TransactionTemplate tx;

    public OrderStateMachine() {
        this(null, null, new AppClock(), null);
    }

    @Autowired
    public OrderStateMachine(
            SlotOccupyStore store,
            SlotOccupyService occupy,
            AppClock clock,
            PlatformTransactionManager txManager
    ) {
        this.store = store;
        this.occupy = occupy;
        this.clock = clock;
        this.tx = txManager == null ? null : new TransactionTemplate(txManager);
    }

    public OrderStateMachine(SlotOccupyStore store, SlotOccupyService occupy, AppClock clock) {
        this.store = store;
        this.occupy = occupy;
        this.clock = clock;
        this.tx = null;
    }

    /** Table lookup. Unknown {@code (from,event)} → 40904. */
    public OrderTransition fire(OrderStatus from, OrderEvent event) {
        if (from == null || event == null) {
            throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "非法状态转移");
        }
        OrderTransition t = TABLE.get(new Key(from, event));
        if (t == null) {
            throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "非法状态转移");
        }
        return t;
    }

    /** CAS status then run side effects in the same TX. */
    public FireResult fire(long orderId, OrderEvent event) {
        return inStoreTx(() -> doFire(orderId, event));
    }

    public static List<OrderTransition> transfers() {
        return List.copyOf(TABLE.values());
    }

    public static boolean listed(OrderStatus from, OrderEvent event) {
        return TABLE.containsKey(new Key(from, event));
    }

    private FireResult doFire(long orderId, OrderEvent event) {
        BookingOrderRef order = store.lockOrderById(orderId);
        if (order == null) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "订单不存在");
        }
        OrderStatus from = OrderStatus.parse(order.status());
        OrderTransition t = fire(from, event);
        int n = store.casOrderStatus(orderId, from.name(), t.to().name(), clock.now());
        if (n == 0) {
            throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "非法状态转移");
        }
        applySides(t, store.lockOrderById(orderId));
        return new FireResult(orderId, from, event, t.to());
    }

    private void applySides(OrderTransition t, BookingOrderRef order) {
        if (occupy == null || order == null) {
            return;
        }
        for (OrderSide side : t.sides()) {
            switch (side) {
                case CONFIRM_PAID -> occupy.confirmPaidSlots(order.id());
                case RELEASE_LOCK -> occupy.releaseLock(order.holdId());
                case RELEASE_UNCONSUMED_START -> occupy.releaseUnconsumed(order.id(), order.startSlotNo());
                case RELEASE_UNCONSUMED_NOW -> occupy.releaseUnconsumed(order.id(), currentSlotNo());
                case RELEASE_ADDON -> {
                    if (order.addOnHoldId() != null) {
                        occupy.releaseAddOnHold(order.addOnHoldId());
                    }
                }
                case NONE, CHECKED_IN_AT, SERVICE_RECORD, ENDED_AT, NO_SHOW_COUNT,
                        REFUND, RESCHEDULE, SWAP_THERAPIST -> {
                    // Timestamps land in casOrderStatus; refund/reschedule/swap are later PRs.
                }
            }
        }
    }

    private int currentSlotNo() {
        var now = clock.now();
        return now.getHour() * 4 + now.getMinute() / 15;
    }

    private <T> T inStoreTx(Supplier<T> work) {
        return inTx(() -> {
            store.beginWork();
            try {
                T result = work.get();
                store.commitWork();
                return result;
            } catch (RuntimeException ex) {
                store.rollbackWork();
                throw ex;
            }
        });
    }

    private <T> T inTx(Supplier<T> work) {
        if (tx == null) {
            return work.get();
        }
        return tx.execute(status -> work.get());
    }

    private static Map<Key, OrderTransition> buildTable() {
        List<OrderTransition> rows = new ArrayList<>();
        rows.add(OrderTransition.of(OrderStatus.PENDING_PAY, OrderEvent.PAY_SUCCESS,
                OrderStatus.BOOKED, OrderSide.CONFIRM_PAID));
        rows.add(OrderTransition.of(OrderStatus.PENDING_PAY, OrderEvent.PAY_TIMEOUT,
                OrderStatus.CLOSED, OrderSide.RELEASE_LOCK));
        rows.add(OrderTransition.of(OrderStatus.PENDING_PAY, OrderEvent.USER_CANCEL,
                OrderStatus.CLOSED, OrderSide.RELEASE_LOCK));
        rows.add(OrderTransition.of(OrderStatus.BOOKED, OrderEvent.CHECK_IN,
                OrderStatus.CHECKED_IN, OrderSide.CHECKED_IN_AT));
        rows.add(OrderTransition.of(OrderStatus.BOOKED, OrderEvent.CANCEL,
                OrderStatus.CANCELLED, OrderSide.REFUND, OrderSide.RELEASE_UNCONSUMED_START));
        rows.add(OrderTransition.of(OrderStatus.BOOKED, OrderEvent.REFUND,
                OrderStatus.CANCELLED, OrderSide.REFUND, OrderSide.RELEASE_UNCONSUMED_START));
        rows.add(OrderTransition.of(OrderStatus.BOOKED, OrderEvent.RESCHEDULE,
                OrderStatus.BOOKED, OrderSide.RESCHEDULE));
        rows.add(OrderTransition.of(OrderStatus.BOOKED, OrderEvent.MARK_NO_SHOW,
                OrderStatus.NO_SHOW, OrderSide.RELEASE_UNCONSUMED_START, OrderSide.NO_SHOW_COUNT));
        rows.add(OrderTransition.of(OrderStatus.CHECKED_IN, OrderEvent.START_SERVICE,
                OrderStatus.IN_SERVICE, OrderSide.SERVICE_RECORD));
        rows.add(OrderTransition.of(OrderStatus.CHECKED_IN, OrderEvent.SWAP_THERAPIST,
                OrderStatus.CHECKED_IN, OrderSide.SWAP_THERAPIST));
        rows.add(OrderTransition.of(OrderStatus.CHECKED_IN, OrderEvent.REFUND,
                OrderStatus.CANCELLED, OrderSide.REFUND, OrderSide.RELEASE_UNCONSUMED_START));
        rows.add(OrderTransition.of(OrderStatus.IN_SERVICE, OrderEvent.COMPLETE_SERVICE,
                OrderStatus.COMPLETED, OrderSide.ENDED_AT));
        rows.add(OrderTransition.of(OrderStatus.IN_SERVICE, OrderEvent.ADD_ON,
                OrderStatus.IN_SERVICE, OrderSide.NONE));
        rows.add(OrderTransition.of(OrderStatus.IN_SERVICE, OrderEvent.ADD_ON_PAY_TIMEOUT,
                OrderStatus.IN_SERVICE, OrderSide.RELEASE_ADDON));
        rows.add(OrderTransition.of(OrderStatus.IN_SERVICE, OrderEvent.SWAP_THERAPIST,
                OrderStatus.IN_SERVICE, OrderSide.SWAP_THERAPIST));
        rows.add(OrderTransition.of(OrderStatus.IN_SERVICE, OrderEvent.ABORT,
                OrderStatus.ABNORMAL, OrderSide.RELEASE_UNCONSUMED_NOW));
        rows.add(OrderTransition.of(OrderStatus.IN_SERVICE, OrderEvent.REFUND,
                OrderStatus.CANCELLED, OrderSide.REFUND, OrderSide.RELEASE_UNCONSUMED_NOW));
        rows.add(OrderTransition.of(OrderStatus.ABNORMAL, OrderEvent.RESOLVE_COMPLETE,
                OrderStatus.COMPLETED, OrderSide.NONE));
        rows.add(OrderTransition.of(OrderStatus.ABNORMAL, OrderEvent.RESOLVE_CANCEL,
                OrderStatus.CANCELLED, OrderSide.RELEASE_UNCONSUMED_NOW));
        rows.add(OrderTransition.of(OrderStatus.COMPLETED, OrderEvent.REVIEW,
                OrderStatus.REVIEWED, OrderSide.NONE));
        rows.add(OrderTransition.of(OrderStatus.COMPLETED, OrderEvent.REFUND,
                OrderStatus.COMPLETED, OrderSide.REFUND));
        rows.add(OrderTransition.of(OrderStatus.CANCELLED, OrderEvent.MARK_NO_SHOW,
                OrderStatus.NO_SHOW, OrderSide.NO_SHOW_COUNT));

        Map<Key, OrderTransition> table = new LinkedHashMap<>();
        for (OrderTransition row : rows) {
            table.put(new Key(row.from(), row.event()), row);
        }
        return Map.copyOf(table);
    }

    private record Key(OrderStatus from, OrderEvent event) {
    }
}
