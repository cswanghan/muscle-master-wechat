package com.jisuodashi.notify;

public final class NotifyDtos {

    private NotifyDtos() {
    }

    public record GrantRequest(String requestId, String templateId, String orderId) {
    }

    public record GrantResponse(String grantId, boolean granted) {
    }
}
