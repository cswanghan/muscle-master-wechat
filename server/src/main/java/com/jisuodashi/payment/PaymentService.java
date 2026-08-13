package com.jisuodashi.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.auth.HumanTask;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.StaffUser;
import com.jisuodashi.auth.StaffUserRepository;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.inventory.SlotOccupyStore;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import com.jisuodashi.order.FireContext;
import com.jisuodashi.order.OrderEvent;
import com.jisuodashi.order.OrderStateMachine;
import com.jisuodashi.order.OrderStatus;
import com.jisuodashi.rbac.PermissionCatalog;
import com.jisuodashi.rbac.StoreScope;
import com.jisuodashi.rbac.StoreScopeContext;
import com.jisuodashi.workflow.WorkflowInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * §3.5 {@code repay} + {@code onWechatNotify}. Payment is 1:1 with a prepay;
 * at most one PENDING row per order.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String TASK_UNKNOWN_PAYMENT = "UNKNOWN_PAYMENT";
    public static final String TASK_AMOUNT_MISMATCH = "AMOUNT_MISMATCH";
    public static final String TASK_REFUND_APPROVE = "REFUND_APPROVE";
    public static final String TASK_REFUND_FAILED = "REFUND_FAILED";
    public static final String REFUND_REASON_CLOSED_PAID = "CLOSED_ORDER_AUTO_REFUND";
    public static final long APPROVAL_THRESHOLD_FEN = 50_000L;
    static final int DEADLOCK_RETRIES = 3;

    private final PaymentStore payments;
    private final SlotOccupyStore orders;
    private final OrderStateMachine machine;
    private final WeChatPayClient wechat;
    private final SnowflakeIdGenerator ids;
    private final AppClock clock;
    private final Duration prepayTtl;
    private final TransactionTemplate tx;
    private final StaffUserRepository staffUsers;

    @Autowired
    public PaymentService(
            PaymentStore payments,
            SlotOccupyStore orders,
            OrderStateMachine machine,
            WeChatPayClient wechat,
            SnowflakeIdGenerator ids,
            AppClock clock,
            AppProperties properties,
            PlatformTransactionManager txManager,
            @Autowired(required = false) StaffUserRepository staffUsers
    ) {
        this(payments, orders, machine, wechat, ids, clock, properties.getWechat().getPrepayTtl(),
                new TransactionTemplate(txManager), staffUsers);
    }

    public PaymentService(
            PaymentStore payments,
            SlotOccupyStore orders,
            OrderStateMachine machine,
            WeChatPayClient wechat,
            SnowflakeIdGenerator ids,
            AppClock clock
    ) {
        this(payments, orders, machine, wechat, ids, clock, Duration.ofHours(2), null, null);
    }

    PaymentService(
            PaymentStore payments,
            SlotOccupyStore orders,
            OrderStateMachine machine,
            WeChatPayClient wechat,
            SnowflakeIdGenerator ids,
            AppClock clock,
            Duration prepayTtl,
            TransactionTemplate tx
    ) {
        this(payments, orders, machine, wechat, ids, clock, prepayTtl, tx, null);
    }

    PaymentService(
            PaymentStore payments,
            SlotOccupyStore orders,
            OrderStateMachine machine,
            WeChatPayClient wechat,
            SnowflakeIdGenerator ids,
            AppClock clock,
            Duration prepayTtl,
            TransactionTemplate tx,
            StaffUserRepository staffUsers
    ) {
        this.payments = payments;
        this.orders = orders;
        this.machine = machine;
        this.wechat = wechat;
        this.ids = ids;
        this.clock = clock;
        this.prepayTtl = prepayTtl == null ? Duration.ofHours(2) : prepayTtl;
        this.tx = tx;
        this.staffUsers = staffUsers;
    }

    public PaymentDtos.PayResponse repay(long customerId, long orderId, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "requestId 不能为空");
        }
        return retryDeadlock(() -> doRepay(customerId, orderId));
    }

    /** After lockNew: prepay outside the occupy TX. Failure must not roll back the lock. */
    public PaymentDtos.PayResponse tryPrepayAfterLock(long customerId, long orderId, String requestId) {
        try {
            return repay(customerId, orderId, requestId);
        } catch (RuntimeException ex) {
            log.warn("prepay after lockNew failed order={}", orderId, ex);
            return null;
        }
    }

    public PaymentDtos.WechatNotifyAck onWechatNotify(String body, Map<String, String> headers) {
        WeChatNotify n = wechat.parseAndVerify(body, headers);
        return retryDeadlock(() -> inBothTx(() -> doNotify(n)));
    }

    public PaymentDtos.NativePayResponse nativePrepay(long customerId, long orderId, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "requestId 不能为空");
        }
        return retryDeadlock(() -> doNative(customerId, orderId));
    }

    /**
     * Walk-in cash: insert CASH SUCCESS then {@code fire(PAY_SUCCESS)} (D23 / §3.2).
     * Replay of an already-cashed order returns the existing row.
     */
    public Payment settleCash(long orderId) {
        return retryDeadlock(() -> inBothTx(() -> doSettleCash(orderId)));
    }

    public PaymentDtos.PaymentView getByPaymentNo(String paymentNo) {
        Payment p = payments.findByPaymentNo(paymentNo);
        if (p == null) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "支付单不存在");
        }
        BookingOrderRef order = orders.findOrderById(p.orderId());
        if (order == null) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "订单不存在");
        }
        StoreScope scope = StoreScopeContext.get();
        if (scope != null) {
            scope.assertContains(order.storeId());
        }
        return new PaymentDtos.PaymentView(
                p.paymentNo(), p.status(), p.amountFen(), String.valueOf(p.orderId()));
    }

    private PaymentDtos.NativePayResponse doNative(long customerId, long orderId) {
        RepayPlan plan = inBothTx(() -> decideNative(customerId, orderId));
        if (plan.reuse != null) {
            return toNative(plan.reuse, true);
        }
        WeChatPayClient.NativePrepay prepay = wechat.nativePrepay(
                plan.paymentNo, plan.amountFen, plan.description);
        PersistResult saved = inBothTx(() -> persistNewNative(customerId, orderId, plan, prepay));
        return toNative(saved.payment(), saved.reused());
    }

    private RepayPlan decideNative(long customerId, long orderId) {
        Payment pending = payments.lockPendingByOrderId(orderId);
        BookingOrderRef order = requirePayableOrder(customerId, orderId);
        LocalDateTime now = clock.now();
        if (pending != null && pending.pending() && !pending.prepayExpired(now)
                && storedCodeUrl(pending) != null) {
            return RepayPlan.reuse(pending);
        }
        if (pending != null && pending.pending()) {
            payments.update(pending.closed(now));
        }
        long id = ids.nextId();
        return RepayPlan.create("P" + id, id, order.payableFen(), "booking-" + order.orderNo());
    }

    private PersistResult persistNewNative(
            long customerId, long orderId, RepayPlan plan, WeChatPayClient.NativePrepay prepay) {
        Payment pending = payments.lockPendingByOrderId(orderId);
        requirePayableOrder(customerId, orderId);
        LocalDateTime now = clock.now();
        if (pending != null && pending.pending() && !pending.prepayExpired(now)
                && storedCodeUrl(pending) != null) {
            return new PersistResult(pending, true);
        }
        if (pending != null && pending.pending()) {
            payments.update(pending.closed(now));
        }
        Payment row = new Payment(
                plan.paymentId, plan.paymentNo, orderId, Payment.CHANNEL_WECHAT, plan.amountFen,
                Payment.PENDING, prepay.prepayId(), null, null, codeUrlJson(prepay.codeUrl()),
                now.plus(prepayTtl), now, now);
        payments.insert(row);
        return new PersistResult(row, false);
    }

    private Payment doSettleCash(long orderId) {
        BookingOrderRef order = orders.lockOrderById(orderId);
        if (order == null) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "订单不存在");
        }
        LocalDateTime now = clock.now();
        Payment existingCash = payments.listByOrderId(orderId).stream()
                .filter(p -> Payment.CHANNEL_CASH.equals(p.channel()) && p.success())
                .findFirst()
                .orElse(null);
        if (existingCash != null) {
            return existingCash;
        }
        if (OrderStatus.parse(order.status()) != OrderStatus.PENDING_PAY) {
            throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "非法状态转移");
        }
        Payment pending = payments.lockPendingByOrderId(orderId);
        if (pending != null && pending.pending()) {
            payments.update(pending.closed(now));
        }
        long id = ids.nextId();
        Payment cash = new Payment(
                id, "P" + id, orderId, Payment.CHANNEL_CASH, order.payableFen(),
                Payment.SUCCESS, null, "CASH", now, null, now, now, now);
        payments.insert(cash);
        machine.fire(orderId, OrderEvent.PAY_SUCCESS, FireContext.system().withPaymentMatched(true));
        return cash;
    }

    /**
     * Payment then order. Channel HTTP is outside the DB TX.
     */
    private PaymentDtos.PayResponse doRepay(long customerId, long orderId) {
        RepayPlan plan = inBothTx(() -> decideRepay(customerId, orderId));
        if (plan.reuse != null) {
            return toPayResponse(plan.reuse, true);
        }
        WeChatPayClient.Prepay prepay = wechat.jsapiPrepay(
                plan.paymentNo, plan.amountFen, plan.description);
        PersistResult saved = inBothTx(() -> persistNewPrepay(customerId, orderId, plan, prepay));
        return toPayResponse(saved.payment, saved.reused);
    }

    private RepayPlan decideRepay(long customerId, long orderId) {
        Payment pending = payments.lockPendingByOrderId(orderId);
        BookingOrderRef order = requirePayableOrder(customerId, orderId);
        LocalDateTime now = clock.now();
        if (pending != null && pending.pending() && !pending.prepayExpired(now)) {
            return RepayPlan.reuse(pending);
        }
        if (pending != null && pending.pending()) {
            payments.update(pending.closed(now));
        }
        long id = ids.nextId();
        return RepayPlan.create("P" + id, id, order.payableFen(), "booking-" + order.orderNo());
    }

    private PersistResult persistNewPrepay(
            long customerId, long orderId, RepayPlan plan, WeChatPayClient.Prepay prepay) {
        Payment pending = payments.lockPendingByOrderId(orderId);
        requirePayableOrder(customerId, orderId);
        LocalDateTime now = clock.now();
        if (pending != null && pending.pending() && !pending.prepayExpired(now)) {
            return new PersistResult(pending, true);
        }
        if (pending != null && pending.pending()) {
            payments.update(pending.closed(now));
        }
        Payment row = new Payment(
                plan.paymentId, plan.paymentNo, orderId, Payment.CHANNEL_WECHAT, plan.amountFen,
                Payment.PENDING, prepay.prepayId(), null, null, null,
                now.plus(prepayTtl), now, now);
        payments.insert(row);
        return new PersistResult(row, false);
    }

    private BookingOrderRef requirePayableOrder(long customerId, long orderId) {
        BookingOrderRef order = orders.lockOrderById(orderId);
        if (order == null || order.customerId() != customerId) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "订单不存在");
        }
        if (OrderStatus.parse(order.status()) != OrderStatus.PENDING_PAY) {
            throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "非法状态转移");
        }
        LocalDateTime now = clock.now();
        if (order.lockExpireAt() != null && !order.lockExpireAt().isAfter(now)) {
            throw new ApiException(ErrorCodes.PAY_EXPIRED, "待支付已过期");
        }
        return order;
    }

    private PaymentDtos.WechatNotifyAck doNotify(WeChatNotify n) {
        Payment p = payments.lockByPaymentNo(n.outTradeNo());
        if (p == null) {
            insertTask(TASK_UNKNOWN_PAYMENT, "unknown_pay:" + n.outTradeNo(), "未知支付回调");
            return PaymentDtos.WechatNotifyAck.success();
        }
        if (p.success()) {
            return PaymentDtos.WechatNotifyAck.success();
        }
        if (n.amountFen() != p.amountFen()) {
            payments.update(p.failed(n.raw(), clock.now()));
            insertTask(TASK_AMOUNT_MISMATCH, "amt:" + p.paymentNo(), "支付金额不符");
            return PaymentDtos.WechatNotifyAck.success();
        }
        BookingOrderRef order = orders.lockOrderById(p.orderId());
        if (order == null) {
            insertTask(TASK_UNKNOWN_PAYMENT, "unknown_pay:" + n.outTradeNo(), "支付单无对应订单");
            return PaymentDtos.WechatNotifyAck.success();
        }
        LocalDateTime now = clock.now();
        OrderStatus status = OrderStatus.parse(order.status());
        if (status == OrderStatus.CLOSED || status == OrderStatus.CANCELLED) {
            Payment paid = p.paid(n.transactionId(), n.raw(), now);
            payments.update(paid);
            enqueueClosedOrderRefund(paid, now);
            return PaymentDtos.WechatNotifyAck.success();
        }
        if (status == OrderStatus.PENDING_PAY) {
            payments.update(p.paid(n.transactionId(), n.raw(), now));
            machine.fire(p.orderId(), OrderEvent.PAY_SUCCESS, FireContext.system().withPaymentMatched(true));
            return PaymentDtos.WechatNotifyAck.success();
        }
        payments.update(p.paid(n.transactionId(), n.raw(), now));
        return PaymentDtos.WechatNotifyAck.success();
    }

    private void enqueueClosedOrderRefund(Payment paid, LocalDateTime now) {
        long wfId = ids.nextId();
        payments.insertWorkflow(new WorkflowInstance(
                wfId,
                WorkflowInstance.TYPE_REFUND,
                paid.orderId(),
                WorkflowInstance.RUNNING,
                "{\"paymentId\":" + paid.id() + "}",
                null,
                now,
                now));
        payments.insertRefund(new Refund(
                ids.nextId(),
                "R" + ids.nextId(),
                paid.id(),
                paid.orderId(),
                paid.amountFen(),
                REFUND_REASON_CLOSED_PAID,
                Refund.PENDING,
                null,
                null,
                now,
                now));
    }

    private void insertTask(String type, String bizKey, String title) {
        HumanTask task = new HumanTask();
        task.setId(ids.nextId());
        task.setTaskType(type);
        task.setBizKey(bizKey);
        task.setTitle(title);
        task.setStatus("OPEN");
        task.setCreatedAt(clock.instant());
        payments.insertHumanTask(task);
    }

    private PaymentDtos.PayResponse toPayResponse(Payment payment, boolean reused) {
        Map<String, String> params = wechat.resign(payment.wxPrepayId());
        return new PaymentDtos.PayResponse(
                String.valueOf(payment.orderId()),
                payment.paymentNo(),
                payment.status(),
                payment.amountFen(),
                reused,
                params);
    }

    private PaymentDtos.NativePayResponse toNative(Payment payment, boolean reused) {
        String codeUrl = storedCodeUrl(payment);
        if (codeUrl == null || codeUrl.isBlank()) {
            throw new ApiException(ErrorCodes.PREPAY_FAILED, "收款码缺失");
        }
        return new PaymentDtos.NativePayResponse(
                String.valueOf(payment.orderId()),
                payment.paymentNo(),
                payment.status(),
                payment.amountFen(),
                reused,
                codeUrl);
    }

    public static String storedCodeUrl(Payment payment) {
        if (payment == null || payment.notifyRaw() == null || payment.notifyRaw().isBlank()) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(payment.notifyRaw());
            JsonNode url = root.get("code_url");
            return url == null || url.isNull() || url.asText().isBlank() ? null : url.asText();
        } catch (Exception e) {
            return null;
        }
    }

    static String codeUrlJson(String codeUrl) {
        try {
            return JSON.writeValueAsString(Map.of("code_url", codeUrl == null ? "" : codeUrl));
        } catch (Exception e) {
            return "{\"code_url\":\"\"}";
        }
    }

    private <T> T inBothTx(Supplier<T> work) {
        return inTx(() -> {
            payments.beginWork();
            orders.beginWork();
            try {
                T result = work.get();
                payments.commitWork();
                orders.commitWork();
                return result;
            } catch (RuntimeException ex) {
                payments.rollbackWork();
                orders.rollbackWork();
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

    private <T> T retryDeadlock(Supplier<T> work) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= DEADLOCK_RETRIES; attempt++) {
            try {
                return work.get();
            } catch (RuntimeException ex) {
                if (!isDeadlock(ex) || attempt == DEADLOCK_RETRIES) {
                    if (isDeadlock(ex)) {
                        throw new ApiException(ErrorCodes.LOCK_CONFLICT, "锁冲突，请重试");
                    }
                    throw ex;
                }
                last = ex;
            }
        }
        throw last != null ? last : new ApiException(ErrorCodes.LOCK_CONFLICT, "锁冲突，请重试");
    }

    static boolean isDeadlock(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof DeadlockLoserDataAccessException) {
                return true;
            }
            if (t instanceof SQLException sql && sql.getErrorCode() == 1213) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null && msg.toLowerCase().contains("deadlock")) {
                return true;
            }
            if (t instanceof DataAccessException && msg != null && msg.contains("1213")) {
                return true;
            }
        }
        return false;
    }

    private record RepayPlan(Payment reuse, String paymentNo, long paymentId, long amountFen, String description) {
        static RepayPlan reuse(Payment payment) {
            return new RepayPlan(payment, null, 0, 0, null);
        }

        static RepayPlan create(String paymentNo, long paymentId, long amountFen, String description) {
            return new RepayPlan(null, paymentNo, paymentId, amountFen, description);
        }
    }

    private record PersistResult(Payment payment, boolean reused) {
    }

    public static String refundNoOf(long paymentId) {
        return "R" + paymentId;
    }

    public PaymentDtos.RefundOutcome refund(
            long orderId, String requestId, long amountFen, String reason, FireContext ctx) {
        if (requestId == null || requestId.isBlank()) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "requestId 不能为空");
        }
        if (amountFen < 1) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "amountFen 无效");
        }
        FireContext fireCtx = enrichRefundContext(ctx);
        RefundPlan plan = retryDeadlock(
                () -> inBothTx(() -> persistRefundsAndFire(orderId, requestId, amountFen, reason, fireCtx)));
        if (!plan.replay() && !plan.waitApproval()) {
            settleChannelRefunds(plan.refunds(), plan.workflowId(), operatorId(fireCtx));
        }
        return toRefundOutcome(orderId, plan.replay());
    }

    public PaymentDtos.RefundOutcome approve(long taskId, String requestId) {
        JwtPrincipal principal = AuthContext.requireStaff();
        return retryDeadlock(() -> doApprove(taskId, requestId, principal.staffId()));
    }

    public List<PaymentDtos.HumanTaskItem> listHumanTasks(String status) {
        String wanted = status == null || status.isBlank() ? "OPEN" : status.trim();
        return payments.listHumanTasks().stream()
                .filter(t -> wanted.equals(t.getStatus()))
                .map(this::toTaskItem)
                .toList();
    }

    private RefundPlan persistRefundsAndFire(
            long orderId, String requestId, long amountFen, String reason, FireContext ctx) {
        BookingOrderRef order = orders.lockOrderById(orderId);
        if (order == null) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "订单不存在");
        }
        StoreScope scope = StoreScopeContext.get();
        if (scope != null) {
            scope.assertContains(order.storeId());
        }
        RefundPlan replay = existingRefundPlan(orderId, requestId);
        if (replay != null) {
            return replay;
        }
        OrderStatus status = OrderStatus.parse(order.status());
        if (status == OrderStatus.PENDING_PAY
                || (status != OrderStatus.BOOKED
                && status != OrderStatus.CHECKED_IN
                && status != OrderStatus.IN_SERVICE
                && status != OrderStatus.COMPLETED)) {
            throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "非法状态转移");
        }
        if ((status == OrderStatus.IN_SERVICE || status == OrderStatus.COMPLETED)
                && !ctx.refundAfterStart()
                && !ctx.privileged()) {
            throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "非法状态转移");
        }
        List<Payment> successPays = payments.listByOrderId(orderId).stream()
                .filter(Payment::success)
                .toList();
        if (successPays.isEmpty()) {
            throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "非法状态转移");
        }
        List<Refund> existing = payments.listRefundsByOrderId(orderId);
        List<Payment> openPays = successPays.stream()
                .filter(p -> existing.stream().noneMatch(r -> r.paymentId() == p.id() && !r.failed()))
                .toList();
        if (openPays.isEmpty()) {
            WorkflowInstance wf = latestRefundWorkflow(orderId);
            return new RefundPlan(true, wf == null ? 0L : wf.id(),
                    WorkflowInstance.WAIT_APPROVAL.equals(wf == null ? "" : wf.status()),
                    existing);
        }
        long totalFen = openPays.stream().mapToLong(Payment::amountFen).sum();
        boolean wait = totalFen >= APPROVAL_THRESHOLD_FEN;
        LocalDateTime now = clock.now();
        long wfId = ids.nextId();
        String context = refundContextJson(requestId, amountFen);
        payments.insertWorkflow(new WorkflowInstance(
                wfId,
                WorkflowInstance.TYPE_REFUND,
                orderId,
                wait ? WorkflowInstance.WAIT_APPROVAL : WorkflowInstance.RUNNING,
                context,
                ctx.actorId(),
                now,
                now));
        String refundStatus = wait ? Refund.WAIT_APPROVAL : Refund.PENDING;
        List<Refund> created = new ArrayList<>();
        for (Payment pay : openPays) {
            String refundNo = refundNoOf(pay.id());
            Refund locked = payments.lockByRefundNo(refundNo);
            if (locked != null && !locked.failed()) {
                created.add(locked);
                continue;
            }
            Refund row = new Refund(
                    ids.nextId(),
                    refundNo,
                    pay.id(),
                    orderId,
                    pay.amountFen(),
                    reason,
                    refundStatus,
                    null,
                    ctx.actorId(),
                    now,
                    now);
            try {
                payments.insertRefund(row);
                created.add(row);
            } catch (RuntimeException ex) {
                Refund raced = payments.findByRefundNo(refundNo);
                if (raced == null) {
                    throw ex;
                }
                created.add(raced);
            }
        }
        if (wait) {
            insertRefundTask(TASK_REFUND_APPROVE, "refund_approve:" + wfId, "退款待审批 ≥¥500",
                    wfId, orderId);
        }
        machine.fire(orderId, OrderEvent.REFUND, ctx);
        return new RefundPlan(false, wfId, wait, created);
    }

    private PaymentDtos.RefundOutcome doApprove(long taskId, String requestId, Long staffId) {
        ApproveHold hold = inBothTx(() -> {
            HumanTask task = payments.findHumanTaskById(taskId);
            if (task == null) {
                throw new ApiException(ErrorCodes.NOT_FOUND, "任务不存在");
            }
            long wfId = task.getWorkflowInstanceId() != null
                    ? task.getWorkflowInstanceId()
                    : parseWorkflowId(task.getBizKey());
            WorkflowInstance wf = payments.findWorkflowById(wfId);
            if (wf == null) {
                throw new ApiException(ErrorCodes.NOT_FOUND, "退款流程不存在");
            }
            if ("DONE".equals(task.getStatus())) {
                return new ApproveHold(wf.orderId(), List.of(), wfId, true);
            }
            if (!TASK_REFUND_APPROVE.equals(task.getTaskType()) || !"OPEN".equals(task.getStatus())) {
                throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "非法状态转移");
            }
            LocalDateTime now = clock.now();
            List<Refund> rows = payments.listRefundsByOrderId(wf.orderId());
            List<Refund> pending = new ArrayList<>();
            for (Refund row : rows) {
                if (row.waitApproval()) {
                    Refund next = row.withStatus(Refund.PENDING, now);
                    payments.updateRefund(next);
                    pending.add(next);
                } else if (row.pending()) {
                    pending.add(row);
                }
            }
            payments.updateWorkflow(wf.withStatus(WorkflowInstance.RUNNING, now));
            task.setStatus("DONE");
            task.setResolvedAt(clock.instant());
            task.setResolvedBy(staffId);
            payments.updateHumanTask(task);
            return new ApproveHold(wf.orderId(), pending, wfId, false);
        });
        if (!hold.replay()) {
            settleChannelRefunds(hold.pending(), hold.workflowId(), staffId);
        }
        return toRefundOutcome(hold.orderId(), hold.replay());
    }

    private void settleChannelRefunds(List<Refund> rows, long workflowId, Long operatorId) {
        boolean anyFail = false;
        for (Refund row : rows) {
            if (row == null || row.success() || row.waitApproval()) {
                continue;
            }
            Payment pay = payments.findById(row.paymentId());
            if (pay == null) {
                pay = payments.listByOrderId(row.orderId()).stream()
                        .filter(p -> p.id() == row.paymentId())
                        .findFirst()
                        .orElse(null);
            }
            if (pay == null) {
                continue;
            }
            if (Payment.CHANNEL_CASH.equals(pay.channel())) {
                markRefundSuccess(row, "CASH", operatorId);
                continue;
            }
            try {
                WeChatPayClient.RefundResult wr = wechat.refund(
                        row.refundNo(), pay.paymentNo(), row.amountFen(), row.reason());
                markRefundSuccess(row, wr == null ? null : wr.wxRefundId(), operatorId);
            } catch (RuntimeException ex) {
                anyFail = true;
                markRefundFailed(row, workflowId, ex.getMessage());
            }
        }
        WorkflowInstance wf = payments.findWorkflowById(workflowId);
        if (wf == null) {
            return;
        }
        LocalDateTime now = clock.now();
        if (anyFail) {
            inBothTx(() -> {
                payments.updateWorkflow(wf.withStatus(WorkflowInstance.MANUAL, now));
                return true;
            });
            return;
        }
        boolean allOk = payments.listRefundsByOrderId(wf.orderId()).stream()
                .filter(r -> !r.failed())
                .allMatch(Refund::success);
        if (allOk) {
            inBothTx(() -> {
                payments.updateWorkflow(wf.withStatus(WorkflowInstance.SUCCESS, now));
                return true;
            });
        }
    }

    private void markRefundSuccess(Refund row, String wxId, Long operatorId) {
        inBothTx(() -> {
            Refund latest = payments.lockByRefundNo(row.refundNo());
            Refund base = latest == null ? row : latest;
            Refund next = new Refund(
                    base.id(), base.refundNo(), base.paymentId(), base.orderId(), base.amountFen(),
                    base.reason(), Refund.SUCCESS, wxId,
                    operatorId != null ? operatorId : base.operatorId(),
                    base.createdAt(), clock.now());
            payments.updateRefund(next);
            return true;
        });
    }

    private void markRefundFailed(Refund row, long workflowId, String detail) {
        inBothTx(() -> {
            Refund latest = payments.lockByRefundNo(row.refundNo());
            Refund base = latest == null ? row : latest;
            payments.updateRefund(base.failed(clock.now()));
            insertRefundTask(
                    TASK_REFUND_FAILED,
                    "refund_fail:" + base.refundNo(),
                    "微信退款失败",
                    workflowId,
                    base.orderId());
            return true;
        });
    }

    private RefundPlan existingRefundPlan(long orderId, String requestId) {
        List<Refund> existing = payments.listRefundsByOrderId(orderId);
        for (WorkflowInstance wf : payments.listWorkflowsByOrderId(orderId)) {
            if (!WorkflowInstance.TYPE_REFUND.equals(wf.workflowType())) {
                continue;
            }
            if (requestId.equals(contextRequestId(wf.contextJson()))) {
                return new RefundPlan(true, wf.id(),
                        WorkflowInstance.WAIT_APPROVAL.equals(wf.status()), existing);
            }
        }
        List<Payment> successPays = payments.listByOrderId(orderId).stream()
                .filter(Payment::success)
                .toList();
        if (successPays.isEmpty() || existing.isEmpty()) {
            return null;
        }
        boolean allCovered = successPays.stream().allMatch(p ->
                existing.stream().anyMatch(r -> r.paymentId() == p.id() && !r.failed()));
        if (!allCovered) {
            return null;
        }
        WorkflowInstance wf = latestRefundWorkflow(orderId);
        return new RefundPlan(true, wf == null ? 0L : wf.id(),
                wf != null && WorkflowInstance.WAIT_APPROVAL.equals(wf.status()), existing);
    }

    private WorkflowInstance latestRefundWorkflow(long orderId) {
        return payments.listWorkflowsByOrderId(orderId).stream()
                .filter(w -> WorkflowInstance.TYPE_REFUND.equals(w.workflowType()))
                .reduce((a, b) -> a.id() > b.id() ? a : b)
                .orElse(null);
    }

    private PaymentDtos.RefundOutcome toRefundOutcome(long orderId, boolean replay) {
        BookingOrderRef order = orders.findOrderById(orderId);
        WorkflowInstance wf = latestRefundWorkflow(orderId);
        return new PaymentDtos.RefundOutcome(
                String.valueOf(orderId),
                order == null ? null : order.status(),
                wf == null ? null : wf.status(),
                payments.listRefundsByOrderId(orderId),
                replay);
    }

    private PaymentDtos.HumanTaskItem toTaskItem(HumanTask task) {
        return new PaymentDtos.HumanTaskItem(
                String.valueOf(task.getId()),
                task.getTaskType(),
                task.getTitle(),
                task.getStatus(),
                task.getOrderId() == null ? null : String.valueOf(task.getOrderId()),
                task.getBizKey());
    }

    private void insertRefundTask(String type, String bizKey, String title, long workflowId, long orderId) {
        HumanTask task = new HumanTask();
        task.setId(ids.nextId());
        task.setTaskType(type);
        task.setBizKey(bizKey);
        task.setTitle(title);
        task.setStatus("OPEN");
        task.setCreatedAt(clock.instant());
        task.setWorkflowInstanceId(workflowId);
        task.setOrderId(orderId);
        payments.insertHumanTask(task);
    }

    private FireContext enrichRefundContext(FireContext ctx) {
        FireContext base = ctx == null ? FireContext.system() : ctx;
        if (base.refundAfterStart() || base.privileged()) {
            return base;
        }
        if (staffUsers == null) {
            return base;
        }
        JwtPrincipal principal = AuthContext.get();
        if (principal == null || principal.staffId() == null) {
            return base;
        }
        StaffUser staff = staffUsers.findById(principal.staffId()).orElse(null);
        if (staff == null) {
            return base;
        }
        Collection<String> held = staff.getPermissionCodes();
        if (held == null || held.isEmpty()) {
            held = PermissionCatalog.forRoles(staff.getRoleCodes());
        }
        if (PermissionCatalog.allows(held, "refund:after_start")) {
            return base.withRefundAfterStart();
        }
        return base;
    }

    private Long operatorId(FireContext ctx) {
        return ctx == null ? null : ctx.actorId();
    }

    static String refundContextJson(String requestId, Long amountFen) {
        try {
            return JSON.writeValueAsString(Map.of(
                    "requestId", requestId == null ? "" : requestId,
                    "amountFen", amountFen == null ? 0L : amountFen));
        } catch (Exception e) {
            return "{\"requestId\":\"" + (requestId == null ? "" : requestId) + "\"}";
        }
    }

    static String contextRequestId(String contextJson) {
        if (contextJson == null || contextJson.isBlank()) {
            return "";
        }
        try {
            JsonNode n = JSON.readTree(contextJson).get("requestId");
            return n == null || n.isNull() ? "" : n.asText();
        } catch (Exception e) {
            return "";
        }
    }

    static long parseWorkflowId(String bizKey) {
        if (bizKey == null) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "任务不存在");
        }
        int colon = bizKey.lastIndexOf(':');
        try {
            return Long.parseLong(colon < 0 ? bizKey : bizKey.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "退款流程不存在");
        }
    }

    private record RefundPlan(boolean replay, long workflowId, boolean waitApproval, List<Refund> refunds) {
    }

    private record ApproveHold(long orderId, List<Refund> pending, long workflowId, boolean replay) {
    }
}
