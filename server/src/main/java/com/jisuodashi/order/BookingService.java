package com.jisuodashi.order;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.inventory.LockNewCommand;
import com.jisuodashi.inventory.LockNewResult;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class BookingService {

    private final SlotOccupyService occupy;
    private final OrderStateMachine machine;

    public BookingService(SlotOccupyService occupy, OrderStateMachine machine) {
        this.occupy = occupy;
        this.machine = machine;
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
        return new BookingDtos.CreateBookingResponse(
                String.valueOf(locked.orderId()),
                locked.orderNo(),
                locked.status(),
                locked.lockExpireAt(),
                locked.payableFen(),
                null);
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

    private static long parseId(String raw, String field) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, field + " 无效");
        }
    }
}
