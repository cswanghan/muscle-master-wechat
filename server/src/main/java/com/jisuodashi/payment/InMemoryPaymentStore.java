package com.jisuodashi.payment;

import com.jisuodashi.auth.HumanTask;
import com.jisuodashi.workflow.WorkflowInstance;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

@Repository
@Profile("dev")
public class InMemoryPaymentStore implements PaymentStore {

    private final Map<String, Payment> byNo = new ConcurrentHashMap<>();
    private final Map<Long, Payment> byId = new ConcurrentHashMap<>();
    private final List<Refund> refunds = new CopyOnWriteArrayList<>();
    private final List<WorkflowInstance> workflows = new CopyOnWriteArrayList<>();
    private final List<HumanTask> tasks = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, ReentrantLock> rowLocks = new ConcurrentHashMap<>();
    private final ThreadLocal<Work> work = new ThreadLocal<>();

    @Override
    public void beginWork() {
        Work w = work.get();
        if (w != null) {
            w.depth++;
            return;
        }
        work.set(new Work());
    }

    @Override
    public void commitWork() {
        Work w = work.get();
        if (w == null) {
            return;
        }
        if (w.depth > 0) {
            w.depth--;
            return;
        }
        w.unlockAll();
        work.remove();
    }

    @Override
    public void rollbackWork() {
        Work w = work.get();
        if (w == null) {
            return;
        }
        if (w.depth > 0) {
            w.depth--;
            return;
        }
        for (int i = w.undos.size() - 1; i >= 0; i--) {
            w.undos.get(i).run();
        }
        w.unlockAll();
        work.remove();
    }

    @Override
    public Payment lockByPaymentNo(String paymentNo) {
        Work w = requireWork();
        lockRow(paymentNo, w);
        return byNo.get(paymentNo);
    }

    @Override
    public Payment findByPaymentNo(String paymentNo) {
        return byNo.get(paymentNo);
    }

    @Override
    public Payment findPendingByOrderId(long orderId) {
        return byNo.values().stream()
                .filter(p -> p.orderId() == orderId && p.pending())
                .findFirst()
                .orElse(null);
    }

    @Override
    public Payment lockPendingByOrderId(long orderId) {
        Payment pending = findPendingByOrderId(orderId);
        if (pending == null) {
            return null;
        }
        return lockByPaymentNo(pending.paymentNo());
    }

    @Override
    public void insert(Payment payment) {
        Work w = requireWork();
        Payment prev = byNo.putIfAbsent(payment.paymentNo(), payment);
        if (prev != null) {
            throw new IllegalStateException("duplicate payment_no " + payment.paymentNo());
        }
        byId.put(payment.id(), payment);
        w.undos.add(() -> {
            byNo.remove(payment.paymentNo(), payment);
            byId.remove(payment.id(), payment);
        });
    }

    @Override
    public void update(Payment payment) {
        Work w = requireWork();
        Payment prev = byNo.get(payment.paymentNo());
        byNo.put(payment.paymentNo(), payment);
        byId.put(payment.id(), payment);
        w.undos.add(() -> {
            if (prev == null) {
                byNo.remove(payment.paymentNo());
                byId.remove(payment.id());
            } else {
                byNo.put(payment.paymentNo(), prev);
                byId.put(payment.id(), prev);
            }
        });
    }

    @Override
    public void insertRefund(Refund refund) {
        Work w = requireWork();
        if (refund.refundNo() != null && refunds.stream().anyMatch(r -> refund.refundNo().equals(r.refundNo()))) {
            throw new IllegalStateException("duplicate refund_no " + refund.refundNo());
        }
        refunds.add(refund);
        w.undos.add(() -> refunds.remove(refund));
    }

    @Override
    public void insertWorkflow(WorkflowInstance instance) {
        Work w = requireWork();
        workflows.add(instance);
        w.undos.add(() -> workflows.remove(instance));
    }

    @Override
    public void insertHumanTask(HumanTask task) {
        Work w = requireWork();
        if (task.getBizKey() != null) {
            boolean exists = tasks.stream().anyMatch(t -> task.getBizKey().equals(t.getBizKey()));
            if (exists) {
                return;
            }
        }
        tasks.add(task);
        w.undos.add(() -> tasks.remove(task));
    }

    @Override
    public List<Payment> listByOrderId(long orderId) {
        return byNo.values().stream().filter(p -> p.orderId() == orderId).toList();
    }

    @Override
    public List<Refund> listRefundsByOrderId(long orderId) {
        return refunds.stream().filter(r -> r.orderId() == orderId).toList();
    }

    @Override
    public List<WorkflowInstance> listWorkflowsByOrderId(long orderId) {
        return workflows.stream().filter(w -> w.orderId() == orderId).toList();
    }

    @Override
    public List<HumanTask> listHumanTasks() {
        return new ArrayList<>(tasks);
    }

    @Override
    public void expirePrepay(String paymentNo, LocalDateTime expireAt) {
        Payment p = byNo.get(paymentNo);
        if (p == null) {
            return;
        }
        Payment next = new Payment(
                p.id(), p.paymentNo(), p.orderId(), p.channel(), p.amountFen(), p.status(),
                p.wxPrepayId(), p.wxTransactionId(), p.paidAt(), p.notifyRaw(),
                expireAt, p.createdAt(), p.updatedAt());
        byNo.put(paymentNo, next);
        byId.put(p.id(), next);
    }

    @Override
    public void clear() {
        byNo.clear();
        byId.clear();
        refunds.clear();
        workflows.clear();
        tasks.clear();
    }

    private Work requireWork() {
        Work w = work.get();
        if (w == null) {
            w = new Work();
            work.set(w);
        }
        return w;
    }

    private void lockRow(String key, Work w) {
        ReentrantLock lock = rowLocks.computeIfAbsent(key, k -> new ReentrantLock());
        if (!w.locks.contains(lock)) {
            lock.lock();
            w.locks.add(lock);
        }
    }

    private static final class Work {
        int depth;
        final List<Runnable> undos = new ArrayList<>();
        final List<ReentrantLock> locks = new ArrayList<>();

        void unlockAll() {
            for (int i = locks.size() - 1; i >= 0; i--) {
                locks.get(i).unlock();
            }
            locks.clear();
        }
    }

    @Override
    public Payment findById(long id) {
        return byId.get(id);
    }

    @Override
    public Refund findByRefundNo(String refundNo) {
        if (refundNo == null) {
            return null;
        }
        return refunds.stream().filter(r -> refundNo.equals(r.refundNo())).findFirst().orElse(null);
    }

    @Override
    public Refund lockByRefundNo(String refundNo) {
        Work w = requireWork();
        lockRow("rf:" + refundNo, w);
        return findByRefundNo(refundNo);
    }

    @Override
    public void updateRefund(Refund refund) {
        Work w = requireWork();
        int idx = -1;
        Refund prev = null;
        for (int i = 0; i < refunds.size(); i++) {
            if (refunds.get(i).id() == refund.id() || refund.refundNo().equals(refunds.get(i).refundNo())) {
                idx = i;
                prev = refunds.get(i);
                break;
            }
        }
        if (idx >= 0) {
            refunds.set(idx, refund);
        } else {
            refunds.add(refund);
        }
        Refund old = prev;
        int at = idx;
        w.undos.add(() -> {
            if (old == null) {
                refunds.remove(refund);
            } else if (at >= 0 && at < refunds.size()) {
                refunds.set(at, old);
            }
        });
    }

    @Override
    public void updateWorkflow(WorkflowInstance instance) {
        Work w = requireWork();
        int idx = -1;
        WorkflowInstance prev = null;
        for (int i = 0; i < workflows.size(); i++) {
            if (workflows.get(i).id() == instance.id()) {
                idx = i;
                prev = workflows.get(i);
                break;
            }
        }
        if (idx >= 0) {
            workflows.set(idx, instance);
        } else {
            workflows.add(instance);
        }
        WorkflowInstance old = prev;
        int at = idx;
        w.undos.add(() -> {
            if (old == null) {
                workflows.remove(instance);
            } else if (at >= 0 && at < workflows.size()) {
                workflows.set(at, old);
            }
        });
    }

    @Override
    public WorkflowInstance findWorkflowById(long id) {
        return workflows.stream().filter(w -> w.id() == id).findFirst().orElse(null);
    }

    @Override
    public HumanTask findHumanTaskById(long id) {
        return tasks.stream().filter(t -> t.getId() == id).findFirst().orElse(null);
    }

    @Override
    public HumanTask lockHumanTaskById(long id) {
        Work w = requireWork();
        lockRow("ht:" + id, w);
        return findHumanTaskById(id);
    }

    @Override
    public void updateHumanTask(HumanTask task) {
        Work w = requireWork();
        HumanTask prev = findHumanTaskById(task.getId());
        if (prev == null) {
            tasks.add(task);
            w.undos.add(() -> tasks.remove(task));
            return;
        }
        int idx = tasks.indexOf(prev);
        if (idx >= 0) {
            tasks.set(idx, task);
        }
        w.undos.add(() -> {
            int at = tasks.indexOf(task);
            if (at >= 0) {
                tasks.set(at, prev);
            }
        });
    }
}
