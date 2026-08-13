package com.jisuodashi.inventory;

public record LockNewResult(
        long orderId,
        String orderNo,
        long holdId,
        long bedId,
        long roomId,
        String status,
        String lockExpireAt,
        long payableFen,
        int startSlotNo,
        int endSlotNo,
        int bufferSlots,
        boolean replay
) {
    public LockNewResult asReplay() {
        return new LockNewResult(
                orderId, orderNo, holdId, bedId, roomId, status, lockExpireAt,
                payableFen, startSlotNo, endSlotNo, bufferSlots, true);
    }
}
