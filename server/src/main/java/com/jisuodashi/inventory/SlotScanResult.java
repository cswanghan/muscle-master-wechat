package com.jisuodashi.inventory;

import java.util.List;

/** Dual-table expired LOCKED scan. Production scan fires PAY_TIMEOUT for PENDING_PAY. */
public record SlotScanResult(
        List<Long> holdIds,
        int orphansFreed,
        int pendingReleased,
        int stalePaid,
        int addonSkipped
) {
    public int holdsSeen() {
        return holdIds.size();
    }
}
