package com.jisuodashi.staff;

import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.TokenType;
import com.jisuodashi.catalog.CatalogModels;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import com.jisuodashi.order.FireContext;
import com.jisuodashi.order.FireResult;
import com.jisuodashi.order.OrderEvent;
import com.jisuodashi.order.OrderStateMachine;
import com.jisuodashi.order.OrderStatus;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class StaffOrderService {

    private final OrderStateMachine machine;
    private final SlotOccupyService occupy;
    private final StaffTherapistLookup therapists;

    public StaffOrderService(
            OrderStateMachine machine,
            SlotOccupyService occupy,
            StaffTherapistLookup therapists) {
        this.machine = machine;
        this.occupy = occupy;
        this.therapists = therapists;
    }

    public StaffDtos.OrderActionResponse start(String orderIdRaw, StaffDtos.OrderActionRequest request) {
        Objects.requireNonNull(request, "request");
        return fire(orderIdRaw, request.requestId(), OrderEvent.START_SERVICE, OrderStatus.IN_SERVICE);
    }

    public StaffDtos.OrderActionResponse complete(String orderIdRaw, StaffDtos.OrderActionRequest request) {
        Objects.requireNonNull(request, "request");
        return fire(orderIdRaw, request.requestId(), OrderEvent.COMPLETE_SERVICE, OrderStatus.COMPLETED);
    }

    private StaffDtos.OrderActionResponse fire(
            String orderIdRaw, String requestId, OrderEvent event, OrderStatus replayStatus) {
        JwtPrincipal principal = AuthContext.requireStaff();
        if (principal.typ() != TokenType.T) {
            throw new ApiException(ErrorCodes.FORBIDDEN, "无功能权限");
        }
        long orderId = parseOrderId(orderIdRaw);
        CatalogModels.Therapist therapist = therapists.requireTherapist(principal);
        BookingOrderRef order = occupy.findOrderById(orderId);
        if (order == null) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "订单不存在");
        }
        FireContext ctx = FireContext.staff(therapist.id(), principal.storeIds());
        try {
            FireResult fired = machine.fire(orderId, event, ctx);
            return new StaffDtos.OrderActionResponse(
                    String.valueOf(fired.orderId()), fired.to().name(), requestId);
        } catch (ApiException ex) {
            if (ex.getCode() == ErrorCodes.ILLEGAL_TRANSITION) {
                BookingOrderRef current = occupy.findOrderById(orderId);
                if (current != null
                        && current.therapistId() == therapist.id()
                        && replayStatus.name().equals(current.status())) {
                    return new StaffDtos.OrderActionResponse(
                            String.valueOf(current.id()), current.status(), requestId);
                }
            }
            throw ex;
        }
    }

    static long parseOrderId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "orderId 无效");
        }
    }
}
