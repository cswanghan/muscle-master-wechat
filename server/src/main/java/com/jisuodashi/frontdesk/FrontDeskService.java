package com.jisuodashi.frontdesk;

import com.jisuodashi.catalog.CatalogRepository;
import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.auth.Customer;
import com.jisuodashi.auth.CustomerMergeService;
import com.jisuodashi.auth.CustomerRepository;
import com.jisuodashi.auth.HumanTask;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.FeatureFlags;
import com.jisuodashi.common.PhoneCrypto;
import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.inventory.ExtendOwnResult;
import com.jisuodashi.inventory.LockNewCommand;
import com.jisuodashi.inventory.LockNewResult;
import com.jisuodashi.inventory.RescheduleCommand;
import com.jisuodashi.inventory.RescheduleResult;
import com.jisuodashi.inventory.ScheduleExceptionService;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.inventory.SlotOccupyStore;
import com.jisuodashi.inventory.SwapTherapistResult;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import com.jisuodashi.order.FireContext;
import com.jisuodashi.order.FireResult;
import com.jisuodashi.order.OrderEvent;
import com.jisuodashi.order.OrderStateMachine;
import com.jisuodashi.order.OrderStatus;
import com.jisuodashi.payment.Payment;
import com.jisuodashi.payment.PaymentDtos;
import com.jisuodashi.payment.PaymentService;
import com.jisuodashi.rbac.PermissionChecker;
import com.jisuodashi.rbac.StoreScope;
import com.jisuodashi.rbac.StoreScopeContext;
import com.jisuodashi.workflow.HumanTaskQueue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class FrontDeskService {

    /** {@code human_task.task_type} opened by ABORT and closed by {@code /resolve}. */
    public static final String TASK_ORDER_ABNORMAL = "ORDER_ABNORMAL";
    public static final String ABNORMAL_BIZ_PREFIX = "abnormal:";
    public static final String ACTION_RESOLVE_COMPLETE = "RESOLVE_COMPLETE";
    public static final String ACTION_RESOLVE_CANCEL = "RESOLVE_CANCEL";
    public static final String ACTION_IGNORE = "IGNORE";

    private final SlotOccupyService occupy;
    private final SlotOccupyStore orders;
    private final OrderStateMachine machine;
    private final PaymentService payments;
    private final CustomerMergeService merge;
    private final CustomerRepository customers;
    private final PhoneCrypto phones;
    private final AppClock clock;
    private final CatalogRepository catalog;
    private FeatureFlags flags;
    private ScheduleExceptionService leaves;
    private HumanTaskQueue tasks;
    private PermissionChecker permissions;
    private SnowflakeIdGenerator ids;
    private TransactionTemplate tx;

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

    @Autowired(required = false)
    public void setFeatureFlags(FeatureFlags flags) {
        this.flags = flags;
    }

    /**
     * Leave approval reaches the desk queue through the same {@code human_task} rows (§2.3),
     * and ABORT/resolve write to that queue too.
     */
    @Autowired(required = false)
    public void setHumanTaskSupport(
            ScheduleExceptionService leaves,
            HumanTaskQueue tasks,
            PermissionChecker permissions,
            SnowflakeIdGenerator ids,
            PlatformTransactionManager txManager) {
        this.leaves = leaves;
        this.tasks = tasks;
        this.permissions = permissions;
        this.ids = ids;
        this.tx = txManager == null ? null : new TransactionTemplate(txManager);
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
                order.serviceDate().toString(),
                order.payableFen(),
                payments.remainingFen(order.id()));
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
                maskFromCustomer(customer),
                order == null ? 0L : payments.remainingFen(order.id()));
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

    public FrontDeskDtos.AddOnResponse addOn(String orderIdRaw, FrontDeskDtos.AddOnRequest req) {
        AuthContext.requireStaff();
        if (flags != null) {
            flags.assertWorkflow(flags.workflowAddOnEnabled(), "加钟");
        }
        if (req == null) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "requestId 不能为空");
        }
        long orderId = parseId(orderIdRaw, "orderId");
        BookingOrderRef order = requireScopedOrder(orders.findOrderById(orderId));
        int minutes = req.durationMinutes() == null ? 0 : req.durationMinutes();
        if (minutes < 15 || minutes % 15 != 0) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "durationMinutes 必须是 15 的倍数且 ≥15");
        }
        String channel = normalizeChannel(req.payChannel());
        int slots = minutes / 15;
        boolean cash = FrontDeskDtos.CASH.equals(channel);
        ExtendOwnResult ext = occupy.extendOwn(
                order.id(), parseId(req.projectId(), "projectId"), slots, cash, req.requestId());
        if (cash) {
            if (!ext.replay()) {
                machine.fire(orderId, OrderEvent.ADD_ON, deskContext().withAddOnPaid());
            }
            return toAddOn(orderId, channel, ext, ext.paymentNo(), null, ext.replay());
        }
        PaymentDtos.NativePayResponse nativePay = payments.tryNativeAddOnPrepay(
                orderId, ext.amountFen(), req.requestId() + ":native");
        if (nativePay == null) {
            throw new ApiException(ErrorCodes.PREPAY_FAILED, "微信预下单失败");
        }
        return toAddOn(orderId, channel, ext, nativePay.paymentNo(), nativePay.codeUrl(),
                ext.replay() || nativePay.reused());
    }

    private FrontDeskDtos.AddOnResponse toAddOn(
            long orderId,
            String channel,
            ExtendOwnResult ext,
            String paymentNo,
            String codeUrl,
            boolean replay
    ) {
        BookingOrderRef latest = orders.findOrderById(orderId);
        return new FrontDeskDtos.AddOnResponse(
                String.valueOf(orderId),
                latest == null ? OrderStatus.IN_SERVICE.name() : latest.status(),
                channel,
                paymentNo,
                codeUrl,
                ext.amountFen(),
                latest == null ? ext.newEndSlotNo() : latest.endSlotNo(),
                replay);
    }

    public FrontDeskDtos.SwapTherapistResponse swapTherapist(
            String orderIdRaw, FrontDeskDtos.SwapTherapistRequest req) {
        AuthContext.requireStaff();
        if (flags != null) {
            flags.assertWorkflow(flags.workflowSwapEnabled(), "换师");
        }
        long orderId = parseId(orderIdRaw, "orderId");
        if (req == null || req.requestId() == null || req.requestId().isBlank()) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "requestId 不能为空");
        }
        if (req.newTherapistId() == null || req.newTherapistId().isBlank()) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "newTherapistId 不能为空");
        }
        BookingOrderRef order = requireScopedOrder(orders.findOrderById(orderId));
        long newTherapistId = parseId(req.newTherapistId(), "newTherapistId");
        SwapTherapistResult swapped = occupy.swapTherapist(
                req.requestId(), order.id(), newTherapistId, req.reason());
        machine.fire(order.id(), OrderEvent.SWAP_THERAPIST, deskContext().withSwapOk());
        BookingOrderRef after = orders.findOrderById(order.id());
        String status = after == null ? order.status() : after.status();
        return new FrontDeskDtos.SwapTherapistResponse(
                String.valueOf(swapped.orderId()),
                status,
                String.valueOf(swapped.oldTherapistId()),
                String.valueOf(swapped.newTherapistId()),
                swapped.fromSlotNo(),
                swapped.replay());
    }

    public FrontDeskDtos.RescheduleResponse reschedule(String orderIdRaw, FrontDeskDtos.RescheduleRequest req) {
        AuthContext.requireStaff();
        if (flags != null) {
            flags.assertWorkflow(flags.workflowRescheduleEnabled(), "改约");
        }
        long orderId = parseId(orderIdRaw, "orderId");
        BookingOrderRef order = requireScopedOrder(orders.findOrderById(orderId));
        JwtPrincipal principal = AuthContext.get();
        RescheduleResult moved = occupy.reschedule(new RescheduleCommand(
                req.requestId(),
                order.id(),
                req.date(),
                req.startSlotNo(),
                parseId(req.therapistId(), "therapistId"),
                principal == null ? null : principal.staffId()));
        machine.fire(orderId, OrderEvent.RESCHEDULE, deskContext().withRescheduleOk());
        BookingOrderRef after = orders.findOrderById(orderId);
        BookingOrderRef view = after == null ? order : after;
        return new FrontDeskDtos.RescheduleResponse(
                String.valueOf(view.id()),
                view.orderNo(),
                view.status(),
                String.valueOf(moved.therapistId()),
                String.valueOf(moved.bedId()),
                String.valueOf(moved.roomId()),
                moved.serviceDate().toString(),
                moved.startSlotNo(),
                moved.endSlotNo(),
                roomName(moved.roomId()),
                bedName(moved.bedId()),
                customerMask(view.customerId()),
                moved.replay());
    }


    public FrontDeskDtos.RefundResponse refund(String orderIdRaw, FrontDeskDtos.RefundRequest req) {
        AuthContext.requireStaff();
        if (flags != null) {
            flags.assertWorkflow(flags.workflowRefundEnabled(), "退款");
        }
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

    /**
     * Dispatches on {@code task_type} (§待办): {@code LEAVE_APPROVE} → the one
     * {@link ScheduleExceptionService#approve} implementation, otherwise the refund workflow.
     *
     * @return {@link FrontDeskDtos.RefundResponse} or {@code ScheduleExceptionDtos.ExceptionView}
     */
    public Object approveTask(String taskIdRaw, FrontDeskDtos.ApproveRequest req) {
        AuthContext.requireStaff();
        long taskId = parseId(taskIdRaw, "taskId");
        String requestId = req == null ? null : req.requestId();
        HumanTask leave = leaveTask(taskId);
        if (leave != null) {
            return leaves.approveTask(leave);
        }
        return toRefund(payments.approve(taskId, requestId));
    }

    public Object denyTask(String taskIdRaw, FrontDeskDtos.ApproveRequest req) {
        AuthContext.requireStaff();
        long taskId = parseId(taskIdRaw, "taskId");
        String requestId = req == null ? null : req.requestId();
        HumanTask leave = leaveTask(taskId);
        if (leave != null) {
            return leaves.rejectTask(leave);
        }
        return toRefund(payments.deny(taskId, requestId));
    }

    /**
     * {@code null} unless the row is a leave approval. The endpoint annotation carries
     * {@code refund:approve}; a leave decision additionally needs {@code schedule:approve}.
     */
    private HumanTask leaveTask(long taskId) {
        if (leaves == null || tasks == null) {
            return null;
        }
        HumanTask task = tasks.findById(taskId);
        if (task == null || !ScheduleExceptionService.TASK_LEAVE_APPROVE.equals(task.getTaskType())) {
            return null;
        }
        if (permissions != null) {
            permissions.require("schedule:approve");
        }
        return task;
    }

    /**
     * §3 {@code IN_SERVICE → ABNORMAL}. Releases the slots at and after {@code nowSlot} and opens
     * the {@code ORDER_ABNORMAL} task the manager later resolves. Idempotent per order.
     */
    public FrontDeskDtos.AbortResponse abortOrder(String orderIdRaw, FrontDeskDtos.AbortRequest req) {
        AuthContext.requireStaff();
        long orderId = parseId(orderIdRaw, "orderId");
        BookingOrderRef order = requireScopedOrder(orders.findOrderById(orderId));
        String bizKey = ABNORMAL_BIZ_PREFIX + orderId;
        String reason = req == null ? null : req.reason();
        return inTasksTx(() -> {
            if (OrderStatus.ABNORMAL.name().equals(order.status())) {
                HumanTask open = tasks == null ? null : tasks.lockByBizKey(bizKey);
                return new FrontDeskDtos.AbortResponse(
                        String.valueOf(orderId), order.status(),
                        open == null ? null : String.valueOf(open.getId()), true);
            }
            FireResult fired = machine.fire(orderId, OrderEvent.ABORT, deskContext());
            HumanTask task = abnormalTask(order, bizKey, reason);
            if (task != null) {
                tasks.insert(task);
            }
            return new FrontDeskDtos.AbortResponse(
                    String.valueOf(orderId), fired.to().name(),
                    task == null ? null : String.valueOf(task.getId()), false);
        });
    }

    /**
     * §待办 异常单出度：{@code RESOLVE_COMPLETE} / {@code RESOLVE_CANCEL} 推订单，
     * {@code IGNORE} 只关任务不动订单。Replay 返回当前状态而不是 40904。
     */
    public FrontDeskDtos.ResolveResponse resolveTask(String taskIdRaw, FrontDeskDtos.ResolveRequest req) {
        AuthContext.requireStaff();
        long taskId = parseId(taskIdRaw, "taskId");
        String action = req == null || req.action() == null ? null : req.action().trim().toUpperCase(Locale.ROOT);
        if (!ACTION_RESOLVE_COMPLETE.equals(action)
                && !ACTION_RESOLVE_CANCEL.equals(action)
                && !ACTION_IGNORE.equals(action)) {
            throw new ApiException(
                    ErrorCodes.BAD_REQUEST, "action 仅支持 RESOLVE_COMPLETE / RESOLVE_CANCEL / IGNORE");
        }
        if (tasks == null) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "任务不存在");
        }
        HumanTask task = tasks.findById(taskId);
        if (task == null) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "任务不存在");
        }
        assertTaskInScope(task);
        BookingOrderRef order = task.getOrderId() == null ? null : orders.findOrderById(task.getOrderId());
        if (!HumanTaskQueue.STATUS_OPEN.equals(task.getStatus())) {
            return new FrontDeskDtos.ResolveResponse(
                    String.valueOf(task.getId()), task.getStatus(), action,
                    order == null ? null : String.valueOf(order.id()),
                    order == null ? null : order.status(), true);
        }
        if (!ACTION_IGNORE.equals(action) && order == null) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "该任务不带订单，只能 IGNORE");
        }
        return inTasksTx(() -> {
            String orderStatus = order == null ? null : order.status();
            if (!ACTION_IGNORE.equals(action)) {
                OrderEvent event = ACTION_RESOLVE_COMPLETE.equals(action)
                        ? OrderEvent.RESOLVE_COMPLETE
                        : OrderEvent.RESOLVE_CANCEL;
                orderStatus = machine.fire(order.id(), event, deskContext().withStoreManager()).to().name();
            }
            String taskStatus = ACTION_IGNORE.equals(action)
                    ? HumanTaskQueue.STATUS_IGNORED
                    : HumanTaskQueue.STATUS_DONE;
            closeTask(taskId, taskStatus);
            return new FrontDeskDtos.ResolveResponse(
                    String.valueOf(taskId), taskStatus, action,
                    order == null ? null : String.valueOf(order.id()), orderStatus, false);
        });
    }

    private HumanTask abnormalTask(BookingOrderRef order, String bizKey, String reason) {
        if (tasks == null || ids == null) {
            return null;
        }
        HumanTask task = new HumanTask();
        task.setId(ids.nextId());
        task.setTaskType(TASK_ORDER_ABNORMAL);
        task.setBizKey(bizKey);
        task.setTitle("异常单待处理 " + order.orderNo());
        task.setDetail(reason == null || reason.isBlank() ? null : "{\"reason\":\"" + escape(reason) + "\"}");
        task.setStatus(HumanTaskQueue.STATUS_OPEN);
        task.setOrderId(order.id());
        task.setStoreId(order.storeId());
        task.setCreatedAt(clock.instant());
        return task;
    }

    private void closeTask(long taskId, String status) {
        HumanTask locked = tasks.lockById(taskId);
        if (locked == null || !HumanTaskQueue.STATUS_OPEN.equals(locked.getStatus())) {
            return;
        }
        JwtPrincipal principal = AuthContext.get();
        locked.setStatus(status);
        locked.setResolvedBy(principal == null ? null : principal.staffId());
        locked.setResolvedAt(clock.instant());
        tasks.update(locked);
    }

    private void assertTaskInScope(HumanTask task) {
        StoreScope scope = StoreScopeContext.get();
        if (scope == null || scope.all()) {
            return;
        }
        Long storeId = task.getStoreId();
        if (storeId == null && task.getOrderId() != null) {
            BookingOrderRef order = orders.findOrderById(task.getOrderId());
            storeId = order == null ? null : order.storeId();
        }
        if (storeId == null) {
            throw new ApiException(ErrorCodes.DATA_SCOPE, "超出数据域");
        }
        scope.assertContains(storeId);
    }

    /** The task write joins the state-machine transaction; dev stores need the explicit unit. */
    private <T> T inTasksTx(java.util.function.Supplier<T> work) {
        if (tasks == null) {
            return work.get();
        }
        java.util.function.Supplier<T> unit = () -> {
            tasks.beginWork();
            try {
                T result = work.get();
                tasks.commitWork();
                return result;
            } catch (RuntimeException ex) {
                tasks.rollbackWork();
                throw ex;
            }
        };
        return tx == null ? unit.get() : tx.execute(status -> unit.get());
    }

    private static String escape(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
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
