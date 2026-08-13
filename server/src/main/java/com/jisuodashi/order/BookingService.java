package com.jisuodashi.order;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.inventory.LockNewCommand;
import com.jisuodashi.inventory.LockNewResult;
import com.jisuodashi.inventory.SlotOccupyService;
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
     * BOOKED / non-owner is 40904 from the closed table / guard.
     */
    public BookingDtos.CancelBookingResponse cancel(
            long customerId, String orderIdRaw, BookingDtos.CancelBookingRequest req) {
        Objects.requireNonNull(req, "request");
        long orderId = parseId(orderIdRaw, "id");
        FireResult fired = machine.fire(orderId, OrderEvent.USER_CANCEL, FireContext.customer(customerId));
        return new BookingDtos.CancelBookingResponse(
                String.valueOf(fired.orderId()), fired.to().name(), req.requestId());
    }

    private static long parseId(String raw, String field) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, field + " 无效");
        }
    }
}
