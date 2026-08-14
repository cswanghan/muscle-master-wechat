package com.jisuodashi.payment;

import com.jisuodashi.auth.HumanTask;
import com.jisuodashi.workflow.HumanTaskQueue;
import org.springframework.stereotype.Component;

/**
 * {@link HumanTaskQueue} over the existing {@link PaymentStore} rows, so producers outside
 * {@code payment} land in the same {@code human_task} table (and, on dev, the same in-memory list).
 */
@Component
public class PaymentStoreHumanTaskQueue implements HumanTaskQueue {

    private final PaymentStore store;

    public PaymentStoreHumanTaskQueue(PaymentStore store) {
        this.store = store;
    }

    @Override
    public void beginWork() {
        store.beginWork();
    }

    @Override
    public void commitWork() {
        store.commitWork();
    }

    @Override
    public void rollbackWork() {
        store.rollbackWork();
    }

    @Override
    public void insert(HumanTask task) {
        store.insertHumanTask(task);
    }

    @Override
    public HumanTask findById(long id) {
        return store.findHumanTaskById(id);
    }

    @Override
    public HumanTask lockById(long id) {
        return store.lockHumanTaskById(id);
    }

    @Override
    public HumanTask lockByBizKey(String bizKey) {
        return store.lockHumanTaskByBizKey(bizKey);
    }

    @Override
    public void update(HumanTask task) {
        store.updateHumanTask(task);
    }
}
