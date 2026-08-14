package com.jisuodashi.workflow;

import com.jisuodashi.auth.HumanTask;

/**
 * The single {@code human_task} queue, seen from outside the payment module.
 *
 * <p>P0 keeps one physical queue so {@code GET /f/human-tasks} lists refund approvals and
 * leave approvals together (§待办). Producers outside {@code payment} — leave approval today —
 * write through this port instead of owning a second table.
 */
public interface HumanTaskQueue {

    String STATUS_OPEN = "OPEN";
    String STATUS_DONE = "DONE";
    String STATUS_IGNORED = "IGNORED";

    /** Task-unit hooks, mirroring the store they delegate to. Nesting is depth-counted. */
    void beginWork();

    void commitWork();

    void rollbackWork();

    /** Idempotent on {@code biz_key}: a replayed insert is a no-op. */
    void insert(HumanTask task);

    HumanTask findById(long id);

    HumanTask lockById(long id);

    HumanTask lockByBizKey(String bizKey);

    void update(HumanTask task);
}
