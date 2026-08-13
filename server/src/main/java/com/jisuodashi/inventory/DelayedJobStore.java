package com.jisuodashi.inventory;

import java.time.LocalDateTime;
import java.util.List;

/** delayed_job claim / complete (D16). Shared by lockNew insert and JobRunner. */
public interface DelayedJobStore {

    /**
     * Claim {@code PENDING ∧ run_at<=now} or {@code RUNNING ∧ lease_until<now}.
     * MySQL uses {@code FOR UPDATE SKIP LOCKED}; in-memory is a mutex CAS.
     */
    List<Long> claimDueJobs(String instanceId, LocalDateTime now, int leaseSeconds, int limit);

    DelayedJobRow findJob(long id);

    int completeJob(long id, String status, String lastError, LocalDateTime now);

    record DelayedJobRow(
            long id,
            String jobType,
            String bizKey,
            String payload,
            LocalDateTime runAt,
            String status,
            String lockedBy,
            LocalDateTime leaseUntil,
            int retryCount,
            String lastError
    ) {
    }
}
