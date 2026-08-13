package com.jisuodashi.order;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.inventory.LockNewCommand;
import com.jisuodashi.inventory.LockNewResult;
import com.jisuodashi.inventory.SlotOccupyService;
import org.springframework.stereotype.Service;

@Service
public class BookingService {

    private final SlotOccupyService occupy;

    public BookingService(SlotOccupyService occupy) {
        this.occupy = occupy;
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

    private static long parseId(String raw, String field) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, field + " 无效");
        }
    }
}
