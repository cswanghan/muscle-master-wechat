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

    public BookingDtos.CreateBookingResponse create(long customerId, BookingDtos.CreateBookingRequest req) {
        LockNewResult locked = occupy.lockNew(new LockNewCommand(
                req.requestId(),
                customerId,
                parseId(req.storeId(), "storeId"),
                parseId(req.therapistId(), "therapistId"),
                parseId(req.projectId(), "projectId"),
                req.date(),
                req.startSlotNo(),
                LockNewCommand.SOURCE_MINI_C));
        Map<String, String> payParams = null;
        if (payments != null) {
            PaymentDtos.PayResponse prepay = payments.tryPrepayAfterLock(
                    customerId, locked.orderId(), req.requestId() + ":prepay");
            if (prepay != null) {
                payParams = prepay.payParams();
            }
        }
        return new BookingDtos.CreateBookingResponse(
                String.valueOf(locked.orderId()),
                locked.orderNo(),
                locked.status(),
                locked.lockExpireAt(),
                locked.payableFen(),
                payParams);
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
