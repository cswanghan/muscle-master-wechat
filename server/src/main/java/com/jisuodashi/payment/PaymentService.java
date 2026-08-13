package com.jisuodashi.payment;

import com.jisuodashi.auth.HumanTask;
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
import com.jisuodashi.rbac.StoreScope;
import com.jisuodashi.rbac.StoreScopeContext;
import com.jisuodashi.workflow.WorkflowInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Supplier;

/**
 * §3.5 {@code repay} + {@code onWechatNotify}. Payment is 1:1 with a prepay;
 * at most one PENDING row per order.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    public static final String TASK_UNKNOWN_PAYMENT = "UNKNOWN_PAYMENT";
    public static final String TASK_AMOUNT_MISMATCH = "AMOUNT_MISMATCH";
    public static final String REFUND_REASON_CLOSED_PAID = "CLOSED_ORDER_AUTO_REFUND";

    private final PaymentStore payments;
    private final SlotOccupyStore orders;
    private final OrderStateMachine machine;
    private final WeChatPayClient wechat;
    private final SnowflakeIdGenerator ids;
    private final AppClock clock;
    private final Duration prepayTtl;
    private final TransactionTemplate tx;

    @Autowired
    public PaymentService(
            PaymentStore payments,
            SlotOccupyStore orders,
            OrderStateMachine machine,
            WeChatPayClient wechat,
            SnowflakeIdGenerator ids,
            AppClock clock,
            AppProperties properties,
            PlatformTransactionManager txManager
    ) {
        this(payments, orders, machine, wechat, ids, clock, properties.getWechat().getPrepayTtl(),
                new TransactionTemplate(txManager));
    }

    public PaymentService(
            PaymentStore payments,
            SlotOccupyStore orders,
            OrderStateMachine machine,
            WeChatPayClient wechat,
            SnowflakeIdGenerator ids,
            AppClock clock
    ) {
        this(payments, orders, machine, wechat, ids, clock, Duration.ofHours(2), null);
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
        this.payments = payments;
        this.orders = orders;
        this.machine = machine;
        this.wechat = wechat;
        this.ids = ids;
        this.clock = clock;
        this.prepayTtl = prepayTtl == null ? Duration.ofHours(2) : prepayTtl;
        this.tx = tx;
    }

    public PaymentDtos.PayResponse repay(long customerId, long orderId, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "requestId 不能为空");
        }
        return inBothTx(() -> doRepay(customerId, orderId));
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
        return inBothTx(() -> doNotify(n));
    }

    public PaymentDtos.PaymentView getByPaymentNo(String paymentNo) {
        Payment p = payments.findByPaymentNo(paymentNo);
        if (p == null) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "支付单不存在");
        }
        BookingOrderRef order = inOrderTx(() -> orders.lockOrderById(p.orderId()));
        StoreScope scope = StoreScopeContext.get();
        if (scope != null && order != null) {
            scope.assertContains(order.storeId());
        }
        return new PaymentDtos.PaymentView(
                p.paymentNo(), p.status(), p.amountFen(), String.valueOf(p.orderId()));
    }

    private PaymentDtos.PayResponse doRepay(long customerId, long orderId) {
        BookingOrderRef order = orders.lockOrderById(orderId);
        if (order == null || order.customerId() != customerId) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "订单不存在");
        }
        OrderStatus status = OrderStatus.parse(order.status());
        if (status != OrderStatus.PENDING_PAY) {
            throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "非法状态转移");
        }
        LocalDateTime now = clock.now();
        if (order.lockExpireAt() != null && !order.lockExpireAt().isAfter(now)) {
            throw new ApiException(ErrorCodes.PAY_EXPIRED, "待支付已过期");
        }
        Payment pending = payments.findPendingByOrderId(orderId);
        if (pending != null && !pending.prepayExpired(now)) {
            return toPayResponse(pending, true);
        }
        if (pending != null) {
            payments.update(pending.closed(now));
        }
        Payment created = insertWechatPrepay(order, now);
        return toPayResponse(created, false);
    }

    private Payment insertWechatPrepay(BookingOrderRef order, LocalDateTime now) {
        long id = ids.nextId();
        String paymentNo = "P" + id;
        WeChatPayClient.Prepay prepay = wechat.jsapiPrepay(
                paymentNo, order.payableFen(), "booking-" + order.orderNo());
        Payment row = new Payment(
                id, paymentNo, order.id(), Payment.CHANNEL_WECHAT, order.payableFen(),
                Payment.PENDING, prepay.prepayId(), null, null, null,
                now.plus(prepayTtl), now, now);
        payments.insert(row);
        return row;
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

    private <T> T inOrderTx(Supplier<T> work) {
        return inTx(() -> {
            orders.beginWork();
            try {
                T result = work.get();
                orders.commitWork();
                return result;
            } catch (RuntimeException ex) {
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
}
