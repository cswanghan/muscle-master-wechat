package com.jisuodashi.payment;

import com.jisuodashi.auth.HumanTask;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.inventory.LockNewResult;
import com.jisuodashi.inventory.OccupyFixtures;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.order.FireContext;
import com.jisuodashi.order.OrderEvent;
import com.jisuodashi.order.OrderStateMachine;
import com.jisuodashi.workflow.WorkflowInstance;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 14);

    @Test
    void singleWechatSuccessRefundsOneRowAndReleasesSlots() {
        Fixture f = booked("rf-one");
        int occBefore = f.store.occupancies.size();
        assertThat(occBefore).isGreaterThan(0);

        PaymentDtos.RefundOutcome out = f.svc.refund(
                f.locked.orderId(), "req-one", 19800, "客户改期无法改约", desk());
        assertThat(out.orderStatus()).isEqualTo("CANCELLED");
        assertThat(out.workflowStatus()).isEqualTo(WorkflowInstance.SUCCESS);
        assertThat(out.refunds()).hasSize(1);
        assertThat(out.refunds().getFirst().status()).isEqualTo(Refund.SUCCESS);
        assertThat(out.refunds().getFirst().refundNo()).isEqualTo(PaymentService.refundNoOf(out.refunds().getFirst().paymentId()));
        assertThat(f.wechat.refundCalls()).hasSize(1);
        assertThat(f.wechat.refundCalls().getFirst().amountFen()).isEqualTo(19800);
        assertThat(f.store.findOrderById(f.locked.orderId()).status()).isEqualTo("CANCELLED");
        assertThat(f.store.occupancies).isEmpty();
    }

    @Test
    void twoSuccessPaymentsCreateTwoWechatRefunds() {
        Fixture f = booked("rf-two");
        insertSuccess(f, 9_900L);
        PaymentDtos.RefundOutcome out = f.svc.refund(
                f.locked.orderId(), "req-two", 29700, "加钟一并退", desk());
        assertThat(out.refunds()).hasSize(2);
        assertThat(out.refunds()).allMatch(r -> Refund.SUCCESS.equals(r.status()));
        assertThat(f.wechat.refundCalls()).hasSize(2);
        assertThat(f.payments.listWorkflowsByOrderId(f.locked.orderId())).hasSize(1);
    }

    @Test
    void cashRefundSucceedsWithoutWechat() {
        Fixture f = fixture("rf-cash");
        f.svc.settleCash(f.locked.orderId());
        assertThat(f.store.findOrderById(f.locked.orderId()).status()).isEqualTo("BOOKED");

        PaymentDtos.RefundOutcome out = f.svc.refund(
                f.locked.orderId(), "req-cash", 19800, "现金退", desk());
        assertThat(out.refunds()).hasSize(1);
        assertThat(out.refunds().getFirst().status()).isEqualTo(Refund.SUCCESS);
        assertThat(out.refunds().getFirst().wxRefundId()).isEqualTo("CASH");
        assertThat(f.wechat.refundCalls()).isEmpty();
        assertThat(f.store.findOrderById(f.locked.orderId()).status()).isEqualTo("CANCELLED");
    }

    @Test
    void amountAtLeast50000WaitsApproval() {
        Fixture f = booked("rf-500");
        insertSuccess(f, 40_000L);
        PaymentDtos.RefundOutcome out = f.svc.refund(
                f.locked.orderId(), "req-500", 59800, "大额", desk());
        assertThat(out.workflowStatus()).isEqualTo(WorkflowInstance.WAIT_APPROVAL);
        assertThat(out.refunds()).isNotEmpty();
        assertThat(out.refunds()).allMatch(Refund::waitApproval);
        assertThat(f.wechat.refundCalls()).isEmpty();
        assertThat(f.payments.listHumanTasks())
                .anyMatch(t -> PaymentService.TASK_REFUND_APPROVE.equals(t.getTaskType())
                        && "OPEN".equals(t.getStatus()));
        assertThat(f.store.findOrderById(f.locked.orderId()).status()).isEqualTo("CANCELLED");
    }

    @Test
    void sameRequestIdAndRefundNoReplayIsIdempotent() {
        Fixture f = booked("rf-idemp");
        PaymentDtos.RefundOutcome first = f.svc.refund(
                f.locked.orderId(), "req-same", 19800, "重复", desk());
        int wechat = f.wechat.refundCalls().size();
        PaymentDtos.RefundOutcome again = f.svc.refund(
                f.locked.orderId(), "req-same", 19800, "重复", desk());
        assertThat(again.replay()).isTrue();
        assertThat(again.refunds()).hasSize(first.refunds().size());
        assertThat(again.refunds().getFirst().refundNo()).isEqualTo(first.refunds().getFirst().refundNo());
        assertThat(f.wechat.refundCalls()).hasSize(wechat);
        assertThat(f.payments.listRefundsByOrderId(f.locked.orderId())).hasSize(1);

        PaymentDtos.RefundOutcome byNo = f.svc.refund(
                f.locked.orderId(), "req-other", 19800, "另一请求", desk());
        assertThat(byNo.replay()).isTrue();
        assertThat(f.payments.listRefundsByOrderId(f.locked.orderId())).hasSize(1);
    }

    @Test
    void inServiceWithoutAfterStartIs40904() {
        Fixture f = booked("rf-svc");
        f.machine.fire(f.locked.orderId(), OrderEvent.CHECK_IN, desk());
        f.machine.fire(f.locked.orderId(), OrderEvent.START_SERVICE,
                FireContext.staff(OccupyFixtures.T1, List.of()));
        assertThat(f.store.findOrderById(f.locked.orderId()).status()).isEqualTo("IN_SERVICE");
        assertThatThrownBy(() -> f.svc.refund(f.locked.orderId(), "req-svc", 19800, "开工后退", desk()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.ILLEGAL_TRANSITION);
        assertThat(f.payments.listRefundsByOrderId(f.locked.orderId())).isEmpty();
        assertThat(f.wechat.refundCalls()).isEmpty();
    }

    @Test
    void pendingPayMustNotUseRefundApi() {
        Fixture f = fixture("rf-pending");
        assertThat(f.store.findOrderById(f.locked.orderId()).status()).isEqualTo("PENDING_PAY");
        assertThatThrownBy(() -> f.svc.refund(f.locked.orderId(), "req-pend", 19800, "未付", desk()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.ILLEGAL_TRANSITION);
        assertThat(f.payments.listRefundsByOrderId(f.locked.orderId())).isEmpty();
        assertThat(f.wechat.refundCalls()).isEmpty();
    }

    @Test
    void wechatRefundFailWritesManualHumanTask() {
        Fixture f = booked("rf-fail");
        f.wechat.failRefunds = true;
        PaymentDtos.RefundOutcome out = f.svc.refund(
                f.locked.orderId(), "req-fail", 19800, "渠道失败", desk());
        assertThat(out.refunds()).hasSize(1);
        assertThat(out.refunds().getFirst().status()).isEqualTo(Refund.FAILED);
        assertThat(out.workflowStatus()).isEqualTo(WorkflowInstance.MANUAL);
        List<HumanTask> tasks = f.payments.listHumanTasks().stream()
                .filter(t -> PaymentService.TASK_REFUND_FAILED.equals(t.getTaskType()))
                .toList();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.getFirst().getBizKey()).startsWith("refund_fail:");
        assertThat(f.store.findOrderById(f.locked.orderId()).status()).isEqualTo("CANCELLED");
    }

    private static FireContext desk() {
        return FireContext.staff(3_100_000_000_000_000_303L, List.of(OccupyFixtures.STORE)).withFrontDesk();
    }

    private static Fixture booked(String requestId) {
        Fixture f = fixture(requestId);
        PaymentDtos.PayResponse pay = f.svc.repay(OccupyFixtures.CUSTOMER, f.locked.orderId(), "pay-" + requestId);
        f.svc.onWechatNotify(PaymentNotifyTest.body(pay.paymentNo(), 19800), Map.of());
        assertThat(f.store.findOrderById(f.locked.orderId()).status()).isEqualTo("BOOKED");
        return f;
    }

    private static void insertSuccess(Fixture f, long amountFen) {
        long id = f.ids.nextId();
        LocalTime nowTime = LocalTime.of(19, 0);
        var now = TODAY.atTime(nowTime);
        f.payments.beginWork();
        f.payments.insert(new Payment(
                id, "P" + id, f.locked.orderId(), Payment.CHANNEL_WECHAT, amountFen, Payment.SUCCESS,
                "prepay-add", "txn-add-" + id, now, null, now.plusHours(2), now, now));
        f.payments.commitWork();
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
