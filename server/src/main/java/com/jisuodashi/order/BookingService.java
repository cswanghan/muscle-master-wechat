package com.jisuodashi.order;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.inventory.LockNewCommand;
import com.jisuodashi.inventory.LockNewResult;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.payment.PaymentDtos;
import com.jisuodashi.payment.PaymentService;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class BookingService {

    private final SlotOccupyService occupy;
    private final PaymentService payments;

    public BookingService(SlotOccupyService occupy, PaymentService payments) {
        this.occupy = occupy;
        this.payments = payments;
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
        PaymentDtos.PayResponse prepay = payments.tryPrepayAfterLock(
                customerId, locked.orderId(), req.requestId() + ":prepay");
        if (prepay != null) {
            payParams = prepay.payParams();
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
        return payments.repay(customerId, orderId, req.requestId());
    }

    private static long parseId(String raw, String field) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, field + " 无效");
        }
    }
}
