package com.jisuodashi.inventory;

public record SwapTherapistResult(
        long orderId,
        long oldTherapistId,
        long newTherapistId,
        int fromSlotNo,
        int endSlotNo,
        boolean replay
) {
    public SwapTherapistResult asReplay() {
        return new SwapTherapistResult(
                orderId, oldTherapistId, newTherapistId, fromSlotNo, endSlotNo, true);
    }
}
