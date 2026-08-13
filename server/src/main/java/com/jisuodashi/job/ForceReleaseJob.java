package com.jisuodashi.job;

import com.jisuodashi.inventory.ReleaseResult;
import com.jisuodashi.inventory.SlotOccupyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Rollback drill / stuck LOCKED. Shares forceFreeByHold with the scan path. */
@Component
public class ForceReleaseJob {

    private static final Logger log = LoggerFactory.getLogger(ForceReleaseJob.class);

    private final SlotOccupyService occupy;

    public ForceReleaseJob(SlotOccupyService occupy) {
        this.occupy = occupy;
    }

    public ReleaseResult run(long holdId) {
        ReleaseResult result = occupy.forceFreeByHold(holdId);
        log.info(
                "ForceReleaseJob hold={} outcome={} occ-{} t-{} b-{}",
                holdId, result.outcome(), result.occupancyDeleted(),
                result.therapistFreed(), result.bedFreed());
        return result;
    }
}
