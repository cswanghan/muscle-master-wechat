package com.jisuodashi.payment;

import com.jisuodashi.auth.HumanTask;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.inventory.DelayedJobStore.DelayedJobRow;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.inventory.LockNewResult;
import com.jisuodashi.inventory.OccupyFixtures;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.job.JobRunner;
import com.jisuodashi.order.FireContext;
import com.jisuodashi.order.OrderEvent;
import com.jisuodashi.order.OrderStateMachine;
import com.jisuodashi.order.OrderStatus;
import com.jisuodashi.workflow.WorkflowInstance;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentNotifyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 14);

    @Test
    void notifyReplayIsIdempotentOnPaymentNo() {
        Fixture f = fixture("n-replay");
        PaymentDtos.PayResponse pay = f.svc.repay(OccupyFixtures.CUSTOMER, f.locked.orderId(), "pay-1");
        PaymentDtos.WechatNotifyAck first = f.svc.onWechatNotify(body(pay.paymentNo(), 19800), Map.of());
        PaymentDtos.WechatNotifyAck replay = f.svc.onWechatNotify(body(pay.paymentNo(), 19800), Map.of());
        assertThat(first.code()).isEqualTo("SUCCESS");
        assertThat(replay.code()).isEqualTo("SUCCESS");
        assertThat(f.store.findOrderByHoldId(f.locked.holdId()).status()).isEqualTo("BOOKED");
        assertThat(f.payments.listByOrderId(f.locked.orderId())).hasSize(1);
        assertThat(f.payments.listByOrderId(f.locked.orderId()).getFirst().success()).isTrue();
        assertThat(f.payments.listRefundsByOrderId(f.locked.orderId())).isEmpty();
        assertThat(f.store.occupancies).hasSize(10);
    }

    @Test
    void amountMismatchPersistsFailedAndHumanTaskThenAcks() {
        Fixture f = fixture("n-amt");
        PaymentDtos.PayResponse pay = f.svc.repay(OccupyFixtures.CUSTOMER, f.locked.orderId(), "pay-amt");
        PaymentDtos.WechatNotifyAck ack = f.svc.onWechatNotify(body(pay.paymentNo(), 1), Map.of());
        assertThat(ack.code()).isEqualTo("SUCCESS");
        Payment row = f.payments.findByPaymentNo(pay.paymentNo());
        assertThat(row.status()).isEqualTo(Payment.FAILED);
        assertThat(f.store.findOrderByHoldId(f.locked.holdId()).status()).isEqualTo("PENDING_PAY");
        List<HumanTask> tasks = f.payments.listHumanTasks().stream()
                .filter(t -> PaymentService.TASK_AMOUNT_MISMATCH.equals(t.getTaskType()))
                .toList();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.getFirst().getBizKey()).isEqualTo("amt:" + pay.paymentNo());

        f.svc.onWechatNotify(body(pay.paymentNo(), 1), Map.of());
        assertThat(f.payments.listHumanTasks().stream()
                .filter(t -> PaymentService.TASK_AMOUNT_MISMATCH.equals(t.getTaskType())))
                .hasSize(1);
    }

    @Test
    void closedPlusPaidEnqueuesRefundAndDoesNotFirePaySuccess() {
        Fixture f = fixture("n-closed");
        PaymentDtos.PayResponse pay = f.svc.repay(OccupyFixtures.CUSTOMER, f.locked.orderId(), "pay-closed");
        f.machine.fire(f.locked.orderId(), OrderEvent.USER_CANCEL, FireContext.customer(OccupyFixtures.CUSTOMER));
        assertThat(f.store.findOrderByHoldId(f.locked.holdId()).status()).isEqualTo("CLOSED");
        assertThat(f.store.occupancies).isEmpty();

        PaymentDtos.WechatNotifyAck ack = f.svc.onWechatNotify(body(pay.paymentNo(), 19800), Map.of());
        assertThat(ack.code()).isEqualTo("SUCCESS");
        assertThat(f.store.findOrderByHoldId(f.locked.holdId()).status()).isEqualTo("CLOSED");
        assertThat(f.store.occupancies).isEmpty();
        Payment row = f.payments.findByPaymentNo(pay.paymentNo());
        assertThat(row.success()).isTrue();
        assertThat(f.payments.listRefundsByOrderId(f.locked.orderId())).hasSize(1);
        assertThat(f.payments.listRefundsByOrderId(f.locked.orderId()).getFirst().status())
                .isEqualTo(Refund.PENDING);
        assertThat(f.payments.listWorkflowsByOrderId(f.locked.orderId())).hasSize(1);
        assertThat(f.payments.listWorkflowsByOrderId(f.locked.orderId()).getFirst().workflowType())
                .isEqualTo(WorkflowInstance.TYPE_REFUND);
        assertThat(f.payments.listWorkflowsByOrderId(f.locked.orderId()).getFirst().status())
                .isEqualTo(WorkflowInstance.RUNNING);
        assertThat(f.payments.listWorkflowsByOrderId(f.locked.orderId()).getFirst().contextJson())
                .contains("\"paymentId\":" + row.id());
    }

    @Test
    void payThenOriginalReleaseLockKeepsBookedAndJobDone() {
        Fixture f = fixture("n-d25");
        PaymentDtos.PayResponse pay = f.svc.repay(OccupyFixtures.CUSTOMER, f.locked.orderId(), "pay-d25");
        f.svc.onWechatNotify(body(pay.paymentNo(), 19800), Map.of());
        assertThat(f.store.findOrderByHoldId(f.locked.holdId()).status()).isEqualTo("BOOKED");
        assertThat(f.store.jobByHold(f.locked.holdId()).status).isEqualTo("DONE");

        DelayedJobRow job = f.store.findJob(f.store.jobByHold(f.locked.holdId()).id);
        JobRunner runner = new JobRunner(null, null, f.store, f.clock, "t", null, f.machine);
        int code = runner.dispatch(job);
        runner.completeJob(job, code, null);

        assertThat(code).isEqualTo(ErrorCodes.ILLEGAL_TRANSITION);
        assertThat(f.store.findOrderByHoldId(f.locked.holdId()).status()).isEqualTo("BOOKED");
        assertThat(f.store.findJob(job.id()).status()).isEqualTo("DONE");
        assertThat(f.store.occupancies).hasSize(10);
        assertThatThrownBy(() -> f.machine.fire(OrderStatus.BOOKED, OrderEvent.PAY_TIMEOUT))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.ILLEGAL_TRANSITION);
    }

    @Test
    void repayReusesPendingPrepayUntilExpire() {
        Fixture f = fixture("n-reuse");
        PaymentDtos.PayResponse first = f.svc.repay(OccupyFixtures.CUSTOMER, f.locked.orderId(), "r1");
        PaymentDtos.PayResponse again = f.svc.repay(OccupyFixtures.CUSTOMER, f.locked.orderId(), "r2");
        assertThat(again.paymentNo()).isEqualTo(first.paymentNo());
        assertThat(again.reused()).isTrue();
        assertThat(again.payParams()).containsKeys("timeStamp", "nonceStr", "package", "signType", "paySign");
        assertThat(again.payParams().get("package")).startsWith("prepay_id=");
        assertThat(f.payments.listByOrderId(f.locked.orderId())).hasSize(1);

        f.payments.expirePrepay(first.paymentNo(), f.clock.now().minusSeconds(1));
        PaymentDtos.PayResponse fresh = f.svc.repay(OccupyFixtures.CUSTOMER, f.locked.orderId(), "r3");
        assertThat(fresh.paymentNo()).isNotEqualTo(first.paymentNo());
        assertThat(fresh.reused()).isFalse();
        assertThat(f.payments.findByPaymentNo(first.paymentNo()).status()).isEqualTo(Payment.CLOSED);
        assertThat(f.payments.findPendingByOrderId(f.locked.orderId()).paymentNo()).isEqualTo(fresh.paymentNo());
    }

    @Test
    void repayRejectsExpiredLockAndNonPending() {
        Fixture f = fixture("n-guard");
        AppClock later = new AppClock(Clock.fixed(
                TODAY.atTime(LocalTime.of(19, 20)).atZone(AppClock.SHANGHAI).toInstant(), AppClock.SHANGHAI));
        PaymentService late = new PaymentService(
                f.payments, f.store, f.machine, f.wechat, f.ids, later);
        assertThatThrownBy(() -> late.repay(OccupyFixtures.CUSTOMER, f.locked.orderId(), "late"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.PAY_EXPIRED);

        Fixture g = fixture("n-booked");
        g.svc.repay(OccupyFixtures.CUSTOMER, g.locked.orderId(), "p");
        g.machine.fire(g.locked.orderId(), OrderEvent.PAY_SUCCESS, FireContext.system().withPaymentMatched(true));
        assertThatThrownBy(() -> g.svc.repay(OccupyFixtures.CUSTOMER, g.locked.orderId(), "again"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.ILLEGAL_TRANSITION);
    }

    @Test
    void pollDoesNotLockAndMissingOrderIs404() {
        Fixture f = fixture("n-poll");
        PaymentDtos.PayResponse pay = f.svc.repay(OccupyFixtures.CUSTOMER, f.locked.orderId(), "poll");
        PaymentDtos.PaymentView view = f.svc.getByPaymentNo(pay.paymentNo());
        assertThat(view.status()).isEqualTo(Payment.PENDING);
        assertThat(view.orderId()).isEqualTo(String.valueOf(f.locked.orderId()));

        f.payments.beginWork();
        f.payments.insert(new Payment(
                99L, "P-orphan", 99L, Payment.CHANNEL_WECHAT, 1, Payment.PENDING,
                "x", null, null, null, f.clock.now().plusHours(2), f.clock.now(), f.clock.now()));
        f.payments.commitWork();
        assertThatThrownBy(() -> f.svc.getByPaymentNo("P-orphan"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.NOT_FOUND);
    }

    @Test
    void unknownPaymentNoWritesHumanTaskAndAcks() {
        Fixture f = fixture("n-unk");
        PaymentDtos.WechatNotifyAck ack = f.svc.onWechatNotify(body("P-missing", 19800), Map.of());
        assertThat(ack.code()).isEqualTo("SUCCESS");
        f.svc.onWechatNotify(body("P-missing", 19800), Map.of());
        List<HumanTask> tasks = f.payments.listHumanTasks().stream()
                .filter(t -> PaymentService.TASK_UNKNOWN_PAYMENT.equals(t.getTaskType()))
                .toList();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.getFirst().getBizKey()).isEqualTo("unknown_pay:P-missing");
    }

    static String body(String paymentNo, long amountFen) {
        return "{\"out_trade_no\":\"" + paymentNo + "\",\"transaction_id\":\"wx_"
                + paymentNo + "\",\"amount_fen\":" + amountFen + "}";
    }

    private static Fixture fixture(String requestId) {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService occupy = OccupyFixtures.service(store);
        AppClock clock = new AppClock(Clock.fixed(
                TODAY.atTime(LocalTime.of(19, 0)).atZone(AppClock.SHANGHAI).toInstant(),
                AppClock.SHANGHAI));
        OrderStateMachine machine = new OrderStateMachine(store, occupy, clock);
        InMemoryPaymentStore payments = new InMemoryPaymentStore();
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(new AppProperties());
        MockWeChatPayClient wechat = new MockWeChatPayClient(clock);
        PaymentService svc = new PaymentService(payments, store, machine, wechat, ids, clock);
        LockNewResult locked = occupy.lockNew(
                OccupyFixtures.cmd(requestId, OccupyFixtures.T1, OccupyFixtures.START_1930));
        return new Fixture(store, occupy, machine, payments, wechat, ids, clock, svc, locked);
    }

    private record Fixture(
            InMemorySlotOccupyStore store,
            SlotOccupyService occupy,
            OrderStateMachine machine,
            InMemoryPaymentStore payments,
            MockWeChatPayClient wechat,
            SnowflakeIdGenerator ids,
            AppClock clock,
            PaymentService svc,
            LockNewResult locked
    ) {
    }
}
