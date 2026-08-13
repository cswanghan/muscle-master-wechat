package com.jisuodashi.job;

import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.inventory.SlotScanResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Every 5 min: UNION therapist_slot ∪ bed_slot expired LOCKED. Does not fire(). */
@Component
public class SlotScanJob {

    private static final Logger log = LoggerFactory.getLogger(SlotScanJob.class);

    private final SlotOccupyService occupy;

    public SlotScanJob(SlotOccupyService occupy) {
        this.occupy = occupy;
    }

    public SlotScanResult run() {
        SlotScanResult result = occupy.scanExpiredLocks();
        if (result.holdsSeen() > 0) {
            log.info(
                    "SlotScanJob holds={} orphans={} pending={} stalePaid={} addonSkipped={}",
                    result.holdsSeen(), result.orphansFreed(), result.pendingReleased(),
                    result.stalePaid(), result.addonSkipped());
        }
        return result;
    }
}
