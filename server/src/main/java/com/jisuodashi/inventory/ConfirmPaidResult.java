package com.jisuodashi.inventory;

/** PAY_SUCCESS sibling: LOCKED → BOOKED/BUFFER and RELEASE_LOCK marked DONE in one TX. */
public record ConfirmPaidResult(
        long orderId,
        long holdId,
        int therapistUpdated,
        int bedUpdated,
        int jobMarkedDone
) {
}
