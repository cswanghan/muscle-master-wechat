package com.jisuodashi.order;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.GrayStores;
import com.jisuodashi.inventory.LockNewCommand;
import com.jisuodashi.inventory.LockNewResult;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import com.jisuodashi.inventory.SlotTimes;
import com.jisuodashi.payment.PaymentDtos;
import com.jisuodashi.payment.PaymentService;
import com.jisuodashi.staff.StaffBoardStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class BookingService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(BookingService.class);

    private final SlotOccupyService occupy;
    private final OrderStateMachine machine;
    private final PaymentService payments;
    private GrayStores gray;
    private StaffBoardStore board;

    public BookingService(SlotOccupyService occupy, OrderStateMachine machine) {
        this(occupy, machine, null);
    }

    @Autowired
    public BookingService(SlotOccupyService occupy, OrderStateMachine machine, PaymentService payments) {
        this.occupy = occupy;
        this.machine = machine;
        this.payments = payments;
    }

    @Autowired(required = false)
    public void setGrayStores(GrayStores gray) {
        this.gray = gray;
    }

    @Autowired(required = false)
    public void setBoard(StaffBoardStore board) {
        this.board = board;
    }

    private PackageBookingPort sessions;

    /** 课包抵扣；setter 注入是为了让 order 层不硬依赖会员域（上层实现下层声明的口）。 */
    @Autowired(required = false)
    public void setSessions(PackageBookingPort sessions) {
        this.sessions = sessions;
    }

    private BookingHooks hooks;

    /** 约课后的动作（课前提醒、课后回访待办）。上层实现，order 只声明口。 */
    @Autowired(required = false)
    public void setHooks(BookingHooks hooks) {
        this.hooks = hooks;
    }

    public BookingDtos.CreateBookingResponse create(long customerId, BookingDtos.CreateBookingRequest req) {
        LockNewResult locked = occupy.lockNew(new LockNewCommand(
                req.requestId(),
                customerId,
                parseId(req.storeId(), "storeId"),
                parseId(req.therapistId(), "therapistId"),
                parseId(req.projectId(), "projectId"),
                req.date(),
                req.startSlotNo(),
                LockNewCommand.SOURCE_MINI_C,
                Boolean.TRUE.equals(req.designated())));
        // 课包抵扣：绑上之后这单不再走微信，完成服务时由 CONSUME_SESSION 扣一次课时。
        // 校验放在锁成功之后 —— 先确保时段真拿到了，再决定这一单怎么结账。
        if (sessions != null && req.memberPackageId() != null && !req.memberPackageId().isBlank()) {
            sessions.bindToOrder(customerId, locked.orderId(), req.memberPackageId());
            // 课已经买过了，没有支付这条腿，直接确认成已预约。
            machine.fire(locked.orderId(), OrderEvent.PAY_SUCCESS,
                    FireContext.system().withPaymentMatched(true));
            afterBooked(locked.orderId());
            return new BookingDtos.CreateBookingResponse(
                    String.valueOf(locked.orderId()),
                    locked.orderNo(),
                    OrderStatus.BOOKED.name(),
                    locked.lockExpireAt(),
                    0L,
                    null);
        }

        Map<String, String> payParams = null;
        String status = locked.status();
        if (payments != null) {
            PaymentDtos.PayResponse prepay = payments.tryPrepayAfterLock(
                    customerId, locked.orderId(), req.requestId() + ":prepay");
            if (prepay != null) {
                payParams = prepay.payParams();
                // 储值卡够付时这一步就把单子结清了。locked.status() 是下单那一刻的快照，
                // 照抄会让客户端拿到一个"待支付"的已付单，用户再点一次支付就是 409。
                if (PaymentDtos.PayResponse.PAID.equals(prepay.status())) {
                    status = OrderStatus.BOOKED.name();
                    afterBooked(locked.orderId());
                }
            }
        }
        return new BookingDtos.CreateBookingResponse(
                String.valueOf(locked.orderId()),
                locked.orderNo(),
                status,
                locked.lockExpireAt(),
                locked.payableFen(),
                payParams);
    }

    /** 约成之后的钩子失败不该把下单打回去 —— 单已经成了，提醒没排上是次要的。 */
    private void afterBooked(long orderId) {
        if (hooks == null) {
            return;
        }
        try {
            hooks.onBooked(orderId);
        } catch (RuntimeException ex) {
            log.warn("post-booking hooks failed order={}", orderId, ex);
        }
    }

    public PaymentDtos.PayResponse pay(long customerId, long orderId, BookingDtos.PayRequest req) {
        if (payments == null) {
            throw new ApiException(ErrorCodes.INTERNAL, "支付未配置");
        }
        return payments.repay(customerId, orderId, req.requestId());
    }

    /**
     * PENDING_PAY only: {@code fire(USER_CANCEL)} → CLOSED + ReleaseLock (Law A).
     * BOOKED / non-owner is 40904. Owner retry after CLOSED replays 200.
     */
    public BookingDtos.CancelBookingResponse cancel(
            long customerId, String orderIdRaw, BookingDtos.CancelBookingRequest req) {
        Objects.requireNonNull(req, "request");
        long orderId = parseId(orderIdRaw, "id");
        try {
            FireResult fired = machine.fire(orderId, OrderEvent.USER_CANCEL, FireContext.customer(customerId));
            return new BookingDtos.CancelBookingResponse(
                    String.valueOf(fired.orderId()), fired.to().name(), req.requestId());
        } catch (ApiException ex) {
            if (ex.getCode() == ErrorCodes.ILLEGAL_TRANSITION) {
                BookingOrderRef order = occupy.findOrderById(orderId);
                if (order != null
                        && order.customerId() == customerId
                        && SlotOccupyService.ORDER_CLOSED.equals(order.status())) {
                    return new BookingDtos.CancelBookingResponse(
                            String.valueOf(order.id()), order.status(), req.requestId());
                }
            }
            throw ex;
        }
    }

    public BookingDtos.BookingListItem get(long customerId, String orderIdRaw) {
        BookingOrderRef row = occupy.findOrderById(parseId(orderIdRaw, "id"));
        if (row == null || row.customerId() != customerId) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "订单不存在");
        }
        if (gray != null && !gray.allows(row.storeId())) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "订单不存在");
        }
        return toListItem(row);
    }

    public BookingDtos.Page<BookingDtos.BookingListItem> list(long customerId, String cursor, Integer limit) {
        int size = limit == null ? 20 : limit;
        if (size < 1 || size > 100) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "limit 须为 1–100");
        }
        Long afterId = parseCursor(cursor);
        List<BookingOrderRef> all = occupy.listOrdersByCustomer(customerId);
        if (gray != null) {
            all = all.stream().filter(row -> gray.allows(row.storeId())).toList();
        }
        List<BookingDtos.BookingListItem> sliced = new ArrayList<>();
        boolean skipping = afterId != null;
        String next = null;
        for (BookingOrderRef row : all) {
            if (skipping) {
                if (row.id() == afterId) {
                    skipping = false;
                }
                continue;
            }
            if (sliced.size() == size) {
                next = String.valueOf(row.id());
                break;
            }
            sliced.add(toListItem(row));
        }
        return new BookingDtos.Page<>(sliced, next);
    }

    private BookingDtos.BookingListItem toListItem(BookingOrderRef row) {
        String expire = row.lockExpireAt() == null
                ? null
                : row.lockExpireAt().atZone(AppClock.SHANGHAI).toOffsetDateTime().toString();
        String projectName = board == null ? null : board.firstProjectName(row.id());
        return new BookingDtos.BookingListItem(
                String.valueOf(row.id()),
                row.orderNo(),
                row.status(),
                row.payableFen(),
                String.valueOf(row.storeId()),
                String.valueOf(row.therapistId()),
                row.serviceDate() == null ? null : row.serviceDate().toString(),
                row.startSlotNo(),
                SlotTimes.toTime(row.startSlotNo()).format(DateTimeFormatter.ofPattern("HH:mm")),
                expire,
                projectName);
    }

    private static Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(cursor);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "cursor 无效");
        }
    }

    private static long parseId(String raw, String field) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, field + " 无效");
        }
    }
}
