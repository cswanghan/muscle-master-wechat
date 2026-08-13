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
}
