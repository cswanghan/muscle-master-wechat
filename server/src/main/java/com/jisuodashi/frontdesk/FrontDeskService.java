package com.jisuodashi.frontdesk;

import com.jisuodashi.catalog.CatalogRepository;
import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.auth.Customer;
import com.jisuodashi.auth.CustomerMergeService;
import com.jisuodashi.auth.CustomerRepository;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.PhoneCrypto;
import com.jisuodashi.inventory.LockNewCommand;
import com.jisuodashi.inventory.LockNewResult;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.inventory.SlotOccupyStore;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import com.jisuodashi.order.FireContext;
import com.jisuodashi.order.FireResult;
import com.jisuodashi.order.OrderEvent;
import com.jisuodashi.order.OrderStateMachine;
import com.jisuodashi.order.OrderStatus;
import com.jisuodashi.payment.Payment;
import com.jisuodashi.payment.PaymentDtos;
import com.jisuodashi.payment.PaymentService;
import com.jisuodashi.rbac.StoreScope;
import com.jisuodashi.rbac.StoreScopeContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class FrontDeskService {

    private final SlotOccupyService occupy;
    private final SlotOccupyStore orders;
    private final OrderStateMachine machine;
    private final PaymentService payments;
    private final CustomerMergeService merge;
    private final CustomerRepository customers;
    private final PhoneCrypto phones;
    private final AppClock clock;
    private final CatalogRepository catalog;

    public FrontDeskService(
            SlotOccupyService occupy,
            SlotOccupyStore orders,
            OrderStateMachine machine,
            PaymentService payments,
            CustomerMergeService merge,
            CustomerRepository customers,
            PhoneCrypto phones,
            AppClock clock,
            CatalogRepository catalog
    ) {
        this.occupy = occupy;
        this.orders = orders;
        this.machine = machine;
        this.payments = payments;
        this.merge = merge;
        this.customers = customers;
        this.phones = phones;
        this.clock = clock;
        this.catalog = catalog;
    }

    public FrontDeskDtos.CheckInResponse checkIn(String orderIdRaw, FrontDeskDtos.CheckInRequest req) {
        AuthContext.requireStaff();
        long orderId = parseId(orderIdRaw, "orderId");
        BookingOrderRef order = requireScopedOrder(orders.findOrderById(orderId));
        confirmKeyword(order, req == null ? null : req.verify(), req == null ? null : req.keyword());
        if (OrderStatus.CHECKED_IN.name().equals(order.status())) {
            return toCheckIn(order);
        }
        try {
            FireResult fired = machine.fire(orderId, OrderEvent.CHECK_IN, deskContext());
            BookingOrderRef after = orders.findOrderById(orderId);
            return after == null
                    ? new FrontDeskDtos.CheckInResponse(
                    String.valueOf(fired.orderId()), fired.to().name(),
                    roomName(order.roomId()), bedName(order.bedId()),
                    customerMask(order.customerId()))
                    : toCheckIn(after);
        } catch (ApiException ex) {
            if (ex.getCode() == ErrorCodes.ILLEGAL_TRANSITION) {
                BookingOrderRef again = orders.findOrderById(orderId);
                if (again != null && OrderStatus.CHECKED_IN.name().equals(again.status())) {
                    return toCheckIn(again);
                }
            }
            throw ex;
        }
    }

    public FrontDeskDtos.LookupResponse lookup(String verify, String keyword) {
        AuthContext.requireStaff();
        if (keyword == null || keyword.isBlank()) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "keyword 不能为空");
        }
        String mode = resolveVerify(verify, keyword);
        StoreScope scope = StoreScopeContext.require();
        List<BookingOrderRef> hits = new ArrayList<>();
        if (FrontDeskDtos.VERIFY_PHONE.equals(mode)) {
            PhoneCrypto.PhoneParts parts = phones.sealMobile(keyword);
            Customer customer = customers.findByPhoneHash(parts.hash()).orElse(null);
            if (customer == null) {
                throw new ApiException(ErrorCodes.NOT_FOUND, "订单不存在");
            }
            for (BookingOrderRef order : orders.listOrdersByCustomerId(customer.getId())) {
                if (scope.contains(order.storeId()) && visibleForDesk(order)) {
                    hits.add(order);
                }
            }
        } else {
            BookingOrderRef order = orders.findOrderByOrderNo(keyword.trim());
            if (order != null && scope.contains(order.storeId()) && visibleForDesk(order)) {
                hits.add(order);
            }
        }
        if (hits.isEmpty()) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "订单不存在");
        }
        return new FrontDeskDtos.LookupResponse(hits.stream().map(this::toPreview).toList());
    }

    public FrontDeskDtos.WalkInResponse walkIn(FrontDeskDtos.WalkInRequest req) {
        AuthContext.requireStaff();
        if (req.phone() == null || req.phone().isBlank()) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "手机号不能为空");
        }
        String channel = normalizeChannel(req.payChannel());
        PhoneCrypto.PhoneParts parts = phones.sealMobile(req.phone());
        Customer customer = merge.merge(null, parts.hash(), parts.cipher());
        if (req.customerName() != null && !req.customerName().isBlank()
                && !req.customerName().equals(customer.getNickname())) {
            customer.setNickname(req.customerName().trim());
            customer.setUpdatedAt(clock.instant());
            customers.update(customer);
        }
        long storeId = resolveStore(req.storeId());
        LockNewResult locked = occupy.lockNew(new LockNewCommand(
                req.requestId(),
                customer.getId(),
                storeId,
                parseId(req.therapistId(), "therapistId"),
                parseId(req.projectId(), "projectId"),
                req.date(),
                req.startSlotNo(),
                LockNewCommand.SOURCE_WALK_IN));
        boolean already = Boolean.TRUE.equals(req.alreadyInStore());
        BookingOrderRef order = orders.findOrderById(locked.orderId());
        if (FrontDeskDtos.CASH.equals(channel)) {
            Payment cash = payments.settleCash(locked.orderId());
            String status = order == null ? locked.status() : order.status();
            if (already) {
                status = checkInAfterPay(locked.orderId());
            } else {
                BookingOrderRef after = orders.findOrderById(locked.orderId());
                status = after == null ? status : after.status();
            }
            BookingOrderRef latest = orders.findOrderById(locked.orderId());
            return toWalkIn(latest == null ? order : latest, customer, channel, cash.paymentNo(),
                    null, already, locked.replay(), status);
        }
        PaymentDtos.NativePayResponse nativePay = payments.nativePrepay(
                customer.getId(), locked.orderId(), req.requestId() + ":native");
        BookingOrderRef latest = orders.findOrderById(locked.orderId());
        return toWalkIn(latest == null ? order : latest, customer, channel, nativePay.paymentNo(),
                nativePay.codeUrl(), already, locked.replay() || nativePay.reused(),
                latest == null ? locked.status() : latest.status());
    }

    private String checkInAfterPay(long orderId) {
        try {
            return machine.fire(orderId, OrderEvent.CHECK_IN, deskContext()).to().name();
        } catch (ApiException ex) {
            if (ex.getCode() == ErrorCodes.ILLEGAL_TRANSITION) {
                BookingOrderRef again = orders.findOrderById(orderId);
                if (again != null && OrderStatus.CHECKED_IN.name().equals(again.status())) {
                    return again.status();
                }
            }
            throw ex;
        }
    }

    private void confirmKeyword(BookingOrderRef order, String verify, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return;
        }
        String mode = resolveVerify(verify, keyword);
        if (FrontDeskDtos.VERIFY_ORDER_NO.equals(mode)) {
            if (!keyword.trim().equals(order.orderNo())) {
                throw new ApiException(ErrorCodes.NOT_FOUND, "订单不存在");
            }
            return;
        }
        PhoneCrypto.PhoneParts parts = phones.sealMobile(keyword);
        Customer customer = customers.findById(order.customerId()).orElse(null);
        if (customer == null || !parts.hash().equals(customer.getPhoneHash())) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "订单不存在");
        }
    }

    private BookingOrderRef requireScopedOrder(BookingOrderRef order) {
        if (order == null) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "订单不存在");
        }
        StoreScope scope = StoreScopeContext.get();
        if (scope != null) {
            scope.assertContains(order.storeId());
        }
        return order;
    }

    private long resolveStore(String storeIdRaw) {
        StoreScope scope = StoreScopeContext.get();
        if (storeIdRaw != null && !storeIdRaw.isBlank()) {
            long storeId = parseId(storeIdRaw, "storeId");
            if (scope != null) {
                scope.assertContains(storeId);
            }
            return storeId;
        }
        if (scope != null && !scope.all() && !scope.storeIds().isEmpty()) {
            return scope.storeIds().getFirst();
        }
        throw new ApiException(ErrorCodes.BAD_REQUEST, "storeId 不能为空");
    }

    private FireContext deskContext() {
        JwtPrincipal principal = AuthContext.get();
        StoreScope scope = StoreScopeContext.get();
        long staffId = principal == null || principal.staffId() == null ? 0L : principal.staffId();
        List<Long> stores = scope == null || scope.all() ? List.of() : scope.storeIds();
        return FireContext.staff(staffId, stores).withFrontDesk();
    }

    private boolean visibleForDesk(BookingOrderRef order) {
        if (!clock.today().equals(order.serviceDate())) {
            return false;
        }
        return OrderStatus.BOOKED.name().equals(order.status())
                || OrderStatus.CHECKED_IN.name().equals(order.status());
    }

    private String roomName(long roomId) {
        if (catalog == null) {
            return FrontDeskNames.roomName(null);
        }
        return FrontDeskNames.roomName(catalog.findRoom(roomId).map(r -> r.name()).orElse(null));
    }

    private String bedName(long bedId) {
        if (catalog == null) {
            return FrontDeskNames.bedName(null);
        }
        return FrontDeskNames.bedName(catalog.findBed(bedId).map(b -> b.name()).orElse(null));
    }

    private FrontDeskDtos.CheckInResponse toCheckIn(BookingOrderRef order) {
        return new FrontDeskDtos.CheckInResponse(
                String.valueOf(order.id()),
                order.status(),
                roomName(order.roomId()),
                bedName(order.bedId()),
                customerMask(order.customerId()));
    }

    private FrontDeskDtos.OrderPreview toPreview(BookingOrderRef order) {
        return new FrontDeskDtos.OrderPreview(
                String.valueOf(order.id()),
                order.orderNo(),
                order.status(),
                roomName(order.roomId()),
                bedName(order.bedId()),
                customerMask(order.customerId()),
                String.valueOf(order.therapistId()),
                order.startSlotNo(),
                order.serviceDate().toString());
    }

    private FrontDeskDtos.WalkInResponse toWalkIn(
            BookingOrderRef order,
            Customer customer,
            String channel,
            String paymentNo,
            String codeUrl,
            boolean already,
            boolean replay,
            String status
    ) {
        long orderId = order == null ? customer.getId() : order.id();
        return new FrontDeskDtos.WalkInResponse(
                String.valueOf(order == null ? orderId : order.id()),
                order == null ? null : order.orderNo(),
                status,
                String.valueOf(customer.getId()),
                channel,
                paymentNo,
                codeUrl,
                order == null ? 0L : order.payableFen(),
                already,
                replay,
                order == null ? null : roomName(order.roomId()),
                order == null ? null : bedName(order.bedId()),
                maskFromCustomer(customer));
    }

    private String customerMask(long customerId) {
        return customers.findById(customerId).map(this::maskFromCustomer).orElse("****");
    }

    private String maskFromCustomer(Customer customer) {
        if (customer.getPhoneCipher() != null && customer.getPhoneCipher().length > 0) {
            return FrontDeskNames.maskPhone(phones.decrypt(customer.getPhoneCipher()));
        }
        return "****";
    }

    static String resolveVerify(String verify, String keyword) {
        if (verify != null && !verify.isBlank()) {
            String mode = verify.trim().toUpperCase(Locale.ROOT);
            if (FrontDeskDtos.VERIFY_PHONE.equals(mode) || FrontDeskDtos.VERIFY_ORDER_NO.equals(mode)) {
                return mode;
            }
            throw new ApiException(ErrorCodes.BAD_REQUEST, "verify 须为 ORDER_NO 或 PHONE");
        }
        String trimmed = keyword == null ? "" : keyword.trim();
        if (trimmed.startsWith("JS")) {
            return FrontDeskDtos.VERIFY_ORDER_NO;
        }
        return FrontDeskDtos.VERIFY_PHONE;
    }

    static String normalizeChannel(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "payChannel 须为 CASH 或 WECHAT");
        }
        String channel = raw.trim().toUpperCase(Locale.ROOT);
        if (!FrontDeskDtos.CASH.equals(channel) && !FrontDeskDtos.WECHAT.equals(channel)) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "payChannel 须为 CASH 或 WECHAT");
        }
        return channel;
    }

    static long parseId(String raw, String field) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, field + " 无效");
        }
    }

    public FrontDeskDtos.RefundResponse refund(String orderIdRaw, FrontDeskDtos.RefundRequest req) {
        AuthContext.requireStaff();
        if (req == null) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "requestId 不能为空");
        }
        long orderId = parseId(orderIdRaw, "orderId");
        requireScopedOrder(orders.findOrderById(orderId));
        var outcome = payments.refund(
                orderId,
                req.requestId(),
                req.amountFen() == null ? 0L : req.amountFen(),
                req.reason(),
                deskContext());
        return toRefund(outcome);
    }

    public FrontDeskDtos.RefundResponse approveTask(String taskIdRaw, FrontDeskDtos.ApproveRequest req) {
        AuthContext.requireStaff();
        long taskId = parseId(taskIdRaw, "taskId");
        String requestId = req == null ? null : req.requestId();
        return toRefund(payments.approve(taskId, requestId));
    }

    public FrontDeskDtos.HumanTaskListResponse listHumanTasks(String status) {
        AuthContext.requireStaff();
        return new FrontDeskDtos.HumanTaskListResponse(
                payments.listHumanTasks(status).stream()
                        .map(t -> new FrontDeskDtos.HumanTaskView(
                                t.id(), t.taskType(), t.title(), t.status(), t.orderId(), t.bizKey()))
                        .toList());
    }

    private static FrontDeskDtos.RefundResponse toRefund(PaymentDtos.RefundOutcome outcome) {
        return new FrontDeskDtos.RefundResponse(
                outcome.orderId(),
                outcome.orderStatus(),
                outcome.workflowStatus(),
                outcome.refunds() == null ? List.of() : outcome.refunds().stream()
                        .map(r -> new FrontDeskDtos.RefundView(
                                r.refundNo(),
                                String.valueOf(r.paymentId()),
                                r.amountFen(),
                                r.status(),
                                r.wxRefundId()))
                        .toList(),
                outcome.replay());
    }
}
