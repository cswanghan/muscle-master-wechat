package com.jisuodashi.inventory;

/** Outcome of ReleaseLock / forceFreeByHold. Never carries a state-machine event (Law A). */
public record ReleaseResult(
        long holdId,
        String outcome,
        int occupancyDeleted,
        int therapistFreed,
        int bedFreed
) {
    public static final String FREED = "FREED";
    public static final String ORPHAN_FREED = "ORPHAN_FREED";
    public static final String SKIPPED_NOT_PENDING = "SKIPPED_NOT_PENDING";
    public static final String IDEMPOTENT = "IDEMPOTENT";

    public boolean skipped() {
        return SKIPPED_NOT_PENDING.equals(outcome);
    }

    public boolean freed() {
        return FREED.equals(outcome) || ORPHAN_FREED.equals(outcome);
    }
}
