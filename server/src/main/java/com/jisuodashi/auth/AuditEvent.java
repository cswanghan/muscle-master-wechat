package com.jisuodashi.auth;

import java.time.Instant;

public record AuditEvent(
        long id,
        String action,
        String resourceType,
        Long resourceId,
        String beforeJson,
        String afterJson,
        Instant createdAt
) {
}
