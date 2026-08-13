package com.jisuodashi.workflow;

import java.time.LocalDateTime;

public record WorkflowInstance(
        long id,
        String workflowType,
        long orderId,
        String status,
        String contextJson,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static final String TYPE_REFUND = "REFUND";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String MANUAL = "MANUAL";
    public static final String WAIT_APPROVAL = "WAIT_APPROVAL";

    public WorkflowInstance withStatus(String next, LocalDateTime now) {
        return new WorkflowInstance(id, workflowType, orderId, next, contextJson, createdBy, createdAt, now);
    }
}
