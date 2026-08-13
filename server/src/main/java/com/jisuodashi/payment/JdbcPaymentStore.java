package com.jisuodashi.payment;

import com.jisuodashi.auth.HumanTask;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.JdbcTimes;
import com.jisuodashi.workflow.WorkflowInstance;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Repository
@Profile("!dev")
public class JdbcPaymentStore implements PaymentStore {

    private final JdbcTemplate jdbc;
    private final Duration prepayTtl;
    private final RowMapper<Payment> paymentMapper;

    public JdbcPaymentStore(JdbcTemplate jdbc, AppProperties properties) {
        this.jdbc = jdbc;
        this.prepayTtl = properties.getWechat().getPrepayTtl() == null
                ? Duration.ofHours(2)
                : properties.getWechat().getPrepayTtl();
        this.paymentMapper = (rs, i) -> {
            LocalDateTime created = local(rs.getTimestamp("created_at"));
            return new Payment(
                    rs.getLong("id"),
                    rs.getString("payment_no"),
                    rs.getLong("order_id"),
                    rs.getString("channel"),
                    rs.getLong("amount_fen"),
                    rs.getString("status"),
                    rs.getString("wx_prepay_id"),
                    rs.getString("wx_transaction_id"),
                    local(rs.getTimestamp("paid_at")),
                    rs.getString("notify_raw"),
                    created == null ? null : created.plus(this.prepayTtl),
                    created,
                    local(rs.getTimestamp("updated_at")));
        };
    }

    @Override
    public void beginWork() {
    }

    @Override
    public void commitWork() {
    }

    @Override
    public void rollbackWork() {
    }

    @Override
    public Payment lockByPaymentNo(String paymentNo) {
        List<Payment> rows = jdbc.query(
                "SELECT * FROM payment WHERE payment_no=? FOR UPDATE", paymentMapper, paymentNo);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public Payment findByPaymentNo(String paymentNo) {
        List<Payment> rows = jdbc.query("SELECT * FROM payment WHERE payment_no=?", paymentMapper, paymentNo);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public Payment findPendingByOrderId(long orderId) {
        List<Payment> rows = jdbc.query(
                "SELECT * FROM payment WHERE order_id=? AND status='PENDING' LIMIT 1",
                paymentMapper, orderId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public Payment lockPendingByOrderId(long orderId) {
        List<Payment> rows = jdbc.query(
                "SELECT * FROM payment WHERE order_id=? AND status='PENDING' LIMIT 1 FOR UPDATE",
                paymentMapper, orderId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public void insert(Payment payment) {
        jdbc.update(
                """
                INSERT INTO payment
                  (id, payment_no, order_id, channel, amount_fen, status, wx_prepay_id,
                   wx_transaction_id, paid_at, notify_raw, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                payment.id(),
                payment.paymentNo(),
                payment.orderId(),
                payment.channel(),
                payment.amountFen(),
                payment.status(),
                payment.wxPrepayId(),
                payment.wxTransactionId(),
                ts(payment.paidAt()),
                payment.notifyRaw(),
                ts(payment.createdAt()),
                ts(payment.updatedAt()));
    }

    @Override
    public void update(Payment payment) {
        jdbc.update(
                """
                UPDATE payment
                   SET status=?, wx_prepay_id=?, wx_transaction_id=?, paid_at=?,
                       notify_raw=?, updated_at=?
                 WHERE id=?
                """,
                payment.status(),
                payment.wxPrepayId(),
                payment.wxTransactionId(),
                ts(payment.paidAt()),
                payment.notifyRaw(),
                ts(payment.updatedAt()),
                payment.id());
    }

    @Override
    public void insertRefund(Refund refund) {
        jdbc.update(
                """
                INSERT INTO refund
                  (id, refund_no, payment_id, order_id, amount_fen, reason, status,
                   wx_refund_id, operator_id, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,
                refund.id(),
                refund.refundNo(),
                refund.paymentId(),
                refund.orderId(),
                refund.amountFen(),
                refund.reason(),
                refund.status(),
                refund.wxRefundId(),
                refund.operatorId(),
                ts(refund.createdAt()),
                ts(refund.updatedAt()));
    }

    @Override
    public void insertWorkflow(WorkflowInstance instance) {
        jdbc.update(
                """
                INSERT INTO workflow_instance
                  (id, workflow_type, order_id, status, context_json, created_by, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """,
                instance.id(),
                instance.workflowType(),
                instance.orderId(),
                instance.status(),
                instance.contextJson(),
                instance.createdBy(),
                ts(instance.createdAt()),
                ts(instance.updatedAt()));
    }

    @Override
    public void insertHumanTask(HumanTask task) {
        try {
            jdbc.update(
                    """
                    INSERT INTO human_task
                      (id, workflow_instance_id, order_id, task_type, biz_key, title, detail, status,
                       assignee_role, store_id, created_at, resolved_at, resolved_by)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    task.getId(),
                    null,
                    null,
                    task.getTaskType(),
                    task.getBizKey(),
                    task.getTitle(),
                    null,
                    task.getStatus(),
                    null,
                    null,
                    JdbcTimes.ts(task.getCreatedAt()),
                    null,
                    null);
        } catch (DuplicateKeyException ignored) {
            jdbc.update("UPDATE human_task SET id=id WHERE biz_key=?", task.getBizKey());
        }
    }

    @Override
    public List<Payment> listByOrderId(long orderId) {
        return jdbc.query("SELECT * FROM payment WHERE order_id=?", paymentMapper, orderId);
    }

    @Override
    public List<Refund> listRefundsByOrderId(long orderId) {
        return jdbc.query("SELECT * FROM refund WHERE order_id=?", REFUND, orderId);
    }

    @Override
    public List<WorkflowInstance> listWorkflowsByOrderId(long orderId) {
        return jdbc.query(
                "SELECT * FROM workflow_instance WHERE order_id=?", WORKFLOW, orderId);
    }

    @Override
    public List<HumanTask> listHumanTasks() {
        return jdbc.query(
                "SELECT id, task_type, biz_key, title, status, created_at FROM human_task",
                (rs, i) -> {
                    HumanTask t = new HumanTask();
                    t.setId(rs.getLong("id"));
                    t.setTaskType(rs.getString("task_type"));
                    t.setBizKey(rs.getString("biz_key"));
                    t.setTitle(rs.getString("title"));
                    t.setStatus(rs.getString("status"));
                    t.setCreatedAt(JdbcTimes.instant(rs.getTimestamp("created_at")));
                    return t;
                });
    }

    private static final RowMapper<Refund> REFUND = (rs, i) -> new Refund(
            rs.getLong("id"),
            rs.getString("refund_no"),
            rs.getLong("payment_id"),
            rs.getLong("order_id"),
            rs.getLong("amount_fen"),
            rs.getString("reason"),
            rs.getString("status"),
            rs.getString("wx_refund_id"),
            rs.getObject("operator_id") == null ? null : rs.getLong("operator_id"),
            local(rs.getTimestamp("created_at")),
            local(rs.getTimestamp("updated_at")));

    private static final RowMapper<WorkflowInstance> WORKFLOW = (rs, i) -> new WorkflowInstance(
            rs.getLong("id"),
            rs.getString("workflow_type"),
            rs.getLong("order_id"),
            rs.getString("status"),
            rs.getString("context_json"),
            rs.getObject("created_by") == null ? null : rs.getLong("created_by"),
            local(rs.getTimestamp("created_at")),
            local(rs.getTimestamp("updated_at")));

    private static Timestamp ts(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static LocalDateTime local(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
