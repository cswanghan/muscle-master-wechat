package com.jisuodashi.inventory;

public record ExtendOwnResult(
        long orderId,
        long addHoldId,
        int oldEndSlotNo,
        int newEndSlotNo,
        int durationMinutes,
        long amountFen,
        boolean cash,
        String paymentNo,
        boolean replay
) {
    public ExtendOwnResult asReplay() {
        return new ExtendOwnResult(
                orderId, addHoldId, oldEndSlotNo, newEndSlotNo, durationMinutes,
                amountFen, cash, paymentNo, true);
    }
}
