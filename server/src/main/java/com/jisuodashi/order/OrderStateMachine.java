package com.jisuodashi.order;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.RequestIds;
import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.inventory.SlotOccupyStore;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import com.jisuodashi.inventory.SlotTimes;
import com.jisuodashi.rbac.AuditLogEntry;
import com.jisuodashi.rbac.AuditLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
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

    static final int NO_SHOW_GRACE_MINUTES = 15;

    private static final Map<Key, OrderTransition> TABLE = buildTable();

    private final SlotOccupyStore store;
    private final SlotOccupyService occupy;
    private final AppClock clock;
    private final AppProperties properties;
    private final TransactionTemplate tx;
    private final AuditLogRepository audits;
    private final SnowflakeIdGenerator ids;

    public OrderStateMachine() {
        this(null, null, new AppClock(), new AppProperties(), null, null, null);
    }

    @Autowired
    public OrderStateMachine(
            SlotOccupyStore store,
            SlotOccupyService occupy,
            AppClock clock,
            AppProperties properties,
            PlatformTransactionManager txManager,
            @Autowired(required = false) AuditLogRepository audits,
            @Autowired(required = false) SnowflakeIdGenerator ids
    ) {
        this.store = store;
        this.occupy = occupy;
        this.clock = clock;
        this.properties = properties == null ? new AppProperties() : properties;
        this.tx = txManager == null ? null : new TransactionTemplate(txManager);
        this.audits = audits;
        this.ids = ids;
    }

    public OrderStateMachine(SlotOccupyStore store, SlotOccupyService occupy, AppClock clock) {
        this(store, occupy, clock, new AppProperties(), null, null, null);
    }

    OrderStateMachine(
            SlotOccupyStore store,
            SlotOccupyService occupy,
            AppClock clock,
            AppProperties properties,
            AuditLogRepository audits,
            SnowflakeIdGenerator ids
    ) {
        this(store, occupy, clock, properties, null, audits, ids);
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
        return fire(orderId, event, FireContext.system());
    }

    public FireResult fire(long orderId, OrderEvent event, FireContext ctx) {
        FireContext context = ctx == null ? FireContext.system() : ctx;
        return inStoreTx(() -> doFire(orderId, event, context));
    }

    public static List<OrderTransition> transfers() {
        return List.copyOf(TABLE.values());
    }

    public static boolean listed(OrderStatus from, OrderEvent event) {
        return TABLE.containsKey(new Key(from, event));
    }

    private FireResult doFire(long orderId, OrderEvent event, FireContext ctx) {
        BookingOrderRef order = store.lockOrderById(orderId);
        if (order == null) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "订单不存在");
        }
        OrderStatus from = OrderStatus.parse(order.status());
        OrderTransition t;
        try {
            t = fire(from, event);
        } catch (ApiException ex) {
            if (ex.getCode() == ErrorCodes.ILLEGAL_TRANSITION) {
                auditIllegal(order, from, event, ctx, "unlisted");
            }
            throw ex;
        }
        String guardFail = guardFailure(from, event, order, ctx);
        if (guardFail != null) {
            auditIllegal(order, from, event, ctx, guardFail);
            throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "非法状态转移");
        }
        int n = store.casOrderStatus(orderId, from.name(), t.to().name(), clock.now());
        if (n == 0) {
            auditIllegal(order, from, event, ctx, "cas-miss");
            throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "非法状态转移");
        }
        applySides(t, store.lockOrderById(orderId));
        return new FireResult(orderId, from, event, t.to());
    }

    String guardFailure(OrderStatus from, OrderEvent event, BookingOrderRef order, FireContext ctx) {
        LocalDateTime now = clock.now();
        LocalDateTime start = serviceStart(order);
        return switch (event) {
            case PAY_SUCCESS -> {
                if (Boolean.FALSE.equals(ctx.paymentMatched())) {
                    yield "payment-mismatch";
                }
                if (ctx.paymentMatched() == null && !ctx.privileged()) {
                    yield "payment-unverified";
                }
                yield null;
            }
            case PAY_TIMEOUT -> {
                if (Boolean.TRUE.equals(ctx.lockExpired()) || ctx.privileged()) {
                    yield null;
                }
                if (order.lockExpireAt() != null && !order.lockExpireAt().isAfter(now)) {
                    yield null;
                }
                yield "lock-not-expired";
            }
            case USER_CANCEL -> (ctx.privileged()
                    || ctx.frontDesk()
                    || (ctx.actor() == FireContext.Actor.CUSTOMER
                    && ctx.actorId() != null && ctx.actorId() == order.customerId()))
                    ? null : "not-owner-or-desk";
            case CHECK_IN -> {
                if (!order.serviceDate().equals(now.toLocalDate())) {
                    yield "not-service-day";
                }
                if (!ctx.privileged() && !ctx.scopedStoreIds().isEmpty()
                        && !ctx.scopedStoreIds().contains(order.storeId())) {
                    yield "store-scope";
                }
                yield null;
            }
            case CANCEL -> minutesUntilStart(now, start) >= properties.getBooking().getCancelFreeMinutes()
                    ? null : "cancel-window";
            case REFUND -> switch (from) {
                case BOOKED -> (ctx.frontDesk() || ctx.storeManager() || ctx.privileged()
                        || (ctx.actor() == FireContext.Actor.CUSTOMER
                        && minutesUntilStart(now, start) >= properties.getBooking().getCancelFreeMinutes()))
                        ? null : "refund-not-allowed";
                case CHECKED_IN -> (ctx.frontDesk() || ctx.storeManager() || ctx.privileged())
                        ? null : "refund-not-desk";
                case IN_SERVICE, COMPLETED -> (ctx.refundAfterStart() || ctx.privileged())
                        ? null : "refund-after-start";
                default -> "refund-not-allowed";
            };
            case RESCHEDULE -> ctx.rescheduleOk() ? null : "reschedule-failed";
            case MARK_NO_SHOW -> {
                if (from == OrderStatus.CANCELLED) {
                    yield (ctx.frontDesk() || ctx.privileged()) ? null : "not-desk";
                }
                yield now.isAfter(start.plusMinutes(NO_SHOW_GRACE_MINUTES)) ? null : "too-early-no-show";
            }
            case START_SERVICE, COMPLETE_SERVICE -> (ctx.privileged()
                    || (ctx.actorId() != null && ctx.actorId() == order.therapistId()))
                    ? null : "not-order-therapist";
            case SWAP_THERAPIST -> ctx.swapOk() ? null : "swap-failed";
            case ADD_ON -> ctx.addOnPaid() ? null : "addon-unpaid";
            case ADD_ON_PAY_TIMEOUT -> (Boolean.TRUE.equals(ctx.addOnHoldExpired())
                    || ctx.privileged()
                    || order.addOnHoldId() != null)
                    ? null : "addon-not-expired";
            case ABORT -> (ctx.frontDesk() || ctx.storeManager() || ctx.privileged())
                    ? null : "not-desk";
            case RESOLVE_COMPLETE, RESOLVE_CANCEL -> (ctx.storeManager() || ctx.privileged())
                    ? null : "not-manager";
            case REVIEW -> ctx.reviewAllowed() ? null : "review-p1";
        };
    }

    private void applySides(OrderTransition t, BookingOrderRef order) {
        if (occupy == null || order == null) {
            return;
        }
        for (OrderSide side : t.sides()) {
            switch (side) {
                case CONFIRM_PAID -> occupy.confirmPaidSlotsInOpenTx(order.id());
                case RELEASE_LOCK -> occupy.releaseLockInOpenTx(order.holdId());
                case RELEASE_UNCONSUMED_START -> occupy.releaseUnconsumedInOpenTx(order.id(), order.startSlotNo());
                case RELEASE_UNCONSUMED_NOW -> occupy.releaseUnconsumedInOpenTx(order.id(), currentSlotNo());
                case RELEASE_ADDON -> {
                    if (order.addOnHoldId() != null) {
                        occupy.releaseAddOnHoldInOpenTx(order.addOnHoldId());
                    }
                }
                case NONE, CHECKED_IN_AT, SERVICE_RECORD, ENDED_AT, NO_SHOW_COUNT,
                        REFUND, RESCHEDULE, SWAP_THERAPIST -> {
                }
            }
        }
    }

    private void auditIllegal(
            BookingOrderRef order, OrderStatus from, OrderEvent event, FireContext ctx, String reason) {
        if (audits == null) {
            return;
        }
        AuditLogEntry entry = new AuditLogEntry();
        entry.setId(ids != null ? ids.nextId() : System.nanoTime());
        entry.setActorId(ctx.actorId());
        entry.setActorType(ctx.actor().name());
        entry.setAction("ILLEGAL_TRANSITION");
        entry.setResourceType("BOOKING_ORDER");
        entry.setResourceId(order.id());
        entry.setStoreId(order.storeId());
        entry.setRequestId(RequestIds.current());
        entry.setBeforeJson("{\"status\":\"" + from + "\",\"event\":\"" + event + "\"}");
        entry.setAfterJson("{\"code\":40904,\"reason\":\"" + reason + "\"}");
        entry.setCreatedAt(clock.instant());
        audits.insert(entry);
    }

    private int currentSlotNo() {
        var now = clock.now();
        return now.getHour() * 4 + now.getMinute() / 15;
    }

    static LocalDateTime serviceStart(BookingOrderRef order) {
        return LocalDateTime.of(order.serviceDate(), SlotTimes.toTime(order.startSlotNo()));
    }

    static long minutesUntilStart(LocalDateTime now, LocalDateTime start) {
        return Duration.between(now, start).toMinutes();
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
