package com.jisuodashi.job;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.inventory.ReleaseResult;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import com.jisuodashi.inventory.SlotScanResult;
import com.jisuodashi.order.FireContext;
import com.jisuodashi.order.OrderEvent;
import com.jisuodashi.order.OrderStateMachine;
import com.jisuodashi.observability.ReleaseScanHeartbeat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Every 5 min: UNION therapist_slot ∪ bed_slot expired LOCKED.
 * PENDING_PAY → {@code fire(PAY_TIMEOUT)} (Law A). Orphans force-free.
 */
@Component
public class SlotScanJob {

    private static final Logger log = LoggerFactory.getLogger(SlotScanJob.class);

    private final SlotOccupyService occupy;
    private final OrderStateMachine machine;
    private ReleaseScanHeartbeat heartbeat;

    public SlotScanJob(SlotOccupyService occupy) {
        this(occupy, null);
    }

    @Autowired
    public SlotScanJob(SlotOccupyService occupy, OrderStateMachine machine) {
        this.occupy = occupy;
        this.machine = machine;
    }

    /** {@code job.release.lag.ms} 的心跳源；单测直接 new 本类时可以不注入。 */
    @Autowired(required = false)
    public void setHeartbeat(ReleaseScanHeartbeat heartbeat) {
        this.heartbeat = heartbeat;
    }

    public SlotScanResult run() {
        SlotScanResult result = machine == null ? occupy.scanExpiredLocks() : scanAndFire();
        if (result.holdsSeen() > 0) {
            log.info(
                    "SlotScanJob holds={} orphans={} pending={} stalePaid={} addonSkipped={}",
                    result.holdsSeen(), result.orphansFreed(), result.pendingReleased(),
                    result.stalePaid(), result.addonSkipped());
        }
        if (heartbeat != null) {
            heartbeat.mark();
        }
        return result;
    }

    /**
     * Design §2.6 path C: orphan → forceFree; add-on → fire(ADD_ON_PAY_TIMEOUT);
     * PENDING_PAY → fire(PAY_TIMEOUT); CLOSED leftover → ReleaseLock; else stale.
     */
    SlotScanResult scanAndFire() {
        List<Long> holds = occupy.findExpiredLockedHoldIds();
        int orphans = 0;
        int pending = 0;
        int stale = 0;
        int addon = 0;
        for (long holdId : holds) {
            BookingOrderRef byHold = occupy.findOrderByHoldId(holdId);
            BookingOrderRef byAddon = occupy.findOrderByAddOnHoldId(holdId);
            if (byHold == null && byAddon == null) {
                occupy.forceFreeByHold(holdId);
                orphans++;
            } else if (byAddon != null && byAddon.addOnHoldId() != null && byAddon.addOnHoldId() == holdId) {
                if (fireAddOnTimeout(byAddon.id())) {
                    addon++;
                } else {
                    stale++;
                    occupy.noteStalePaidLocked();
                }
            } else if (byHold != null && SlotOccupyService.ORDER_PENDING_PAY.equals(byHold.status())) {
                if (firePayTimeout(byHold.id())) {
                    pending++;
                } else {
                    stale++;
                    occupy.noteStalePaidLocked();
                }
            } else if (byHold != null && SlotOccupyService.ORDER_CLOSED.equals(byHold.status())) {
                ReleaseResult r = occupy.releaseLock(holdId);
                if (r.skipped()) {
                    stale++;
                    occupy.noteStalePaidLocked();
                } else {
                    pending++;
                }
            } else {
                stale++;
                occupy.noteStalePaidLocked();
            }
        }
        return new SlotScanResult(List.copyOf(holds), orphans, pending, stale, addon);
    }

    private boolean firePayTimeout(long orderId) {
        try {
            machine.fire(orderId, OrderEvent.PAY_TIMEOUT, FireContext.job());
            return true;
        } catch (ApiException ex) {
            if (ex.getCode() == ErrorCodes.ILLEGAL_TRANSITION) {
                return false;
            }
            log.warn("SlotScanJob fire(PAY_TIMEOUT) order={} code={}", orderId, ex.getCode());
            return false;
        }
    }

    private boolean fireAddOnTimeout(long orderId) {
        try {
            machine.fire(orderId, OrderEvent.ADD_ON_PAY_TIMEOUT, FireContext.job());
            return true;
        } catch (ApiException ex) {
            if (ex.getCode() == ErrorCodes.ILLEGAL_TRANSITION) {
                return true;
            }
            log.warn("SlotScanJob fire(ADD_ON_PAY_TIMEOUT) order={} code={}", orderId, ex.getCode());
            return false;
        }
    }
}
