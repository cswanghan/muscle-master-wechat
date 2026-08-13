package com.jisuodashi.payment;

import com.jisuodashi.auth.HumanTask;
import com.jisuodashi.workflow.WorkflowInstance;

import java.time.LocalDateTime;
import java.util.List;

/** Persistence for payment / refund / notify-side human_task + REFUND workflow. */
public interface PaymentStore {

    void beginWork();

    void commitWork();

    void rollbackWork();

    /** {@code SELECT … FOR UPDATE} by {@code payment_no}. */
    Payment lockByPaymentNo(String paymentNo);

    Payment findByPaymentNo(String paymentNo);

    Payment findPendingByOrderId(long orderId);

    /** {@code SELECT … FOR UPDATE} the at-most-one PENDING row of the order. */
    Payment lockPendingByOrderId(long orderId);

    void insert(Payment payment);

    void update(Payment payment);

    void insertRefund(Refund refund);

    void insertWorkflow(WorkflowInstance instance);

    /** {@code INSERT … ON DUPLICATE KEY UPDATE id=id} on {@code biz_key}. */
    void insertHumanTask(HumanTask task);

    List<Payment> listByOrderId(long orderId);

    List<Refund> listRefundsByOrderId(long orderId);

    List<WorkflowInstance> listWorkflowsByOrderId(long orderId);

    List<HumanTask> listHumanTasks();

    default void expirePrepay(String paymentNo, LocalDateTime expireAt) {
    }

    default void clear() {
    }

    Payment findById(long id);

    Refund findByRefundNo(String refundNo);

    Refund lockByRefundNo(String refundNo);

    void updateRefund(Refund refund);

    void updateWorkflow(WorkflowInstance instance);

    WorkflowInstance findWorkflowById(long id);

    HumanTask findHumanTaskById(long id);

    HumanTask lockHumanTaskById(long id);

    void updateHumanTask(HumanTask task);
}
