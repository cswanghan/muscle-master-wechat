package com.jisuodashi.job;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.inventory.DelayedJobStore;
import com.jisuodashi.inventory.DelayedJobStore.DelayedJobRow;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.inventory.SlotOccupyStore;
import com.jisuodashi.order.FireContext;
import com.jisuodashi.order.OrderEvent;
import com.jisuodashi.order.OrderStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Single runner (D16). Gated by {@code app.jobs.enabled}.
 * Claim: {@code PENDING ∧ run_at<=now} or {@code RUNNING ∧ lease_until<now}.
 * {@code 40904} is DONE. RELEASE_LOCK / RELEASE_ADDON only {@code fire()} (Law A).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.jobs", name = "enabled", havingValue = "true")
public class JobRunner {

    public static final int LEASE_SECONDS = 60;
    public static final int CLAIM_LIMIT = 50;

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final SlotGenerateJob slotGenerateJob;
    private final SlotScanJob slotScanJob;
    private final DelayedJobStore delayedJobs;
    private final SlotOccupyStore occupyStore;
    private final OrderStateMachine machine;
    private final AppClock clock;
    private final String instanceId;
    private final TransactionTemplate tx;

    @Autowired
    public JobRunner(
            SlotGenerateJob slotGenerateJob,
            SlotScanJob slotScanJob,
            DelayedJobStore delayedJobs,
            AppClock clock,
            AppProperties properties,
            PlatformTransactionManager txManager,
            OrderStateMachine machine
    ) {
        this(slotGenerateJob, slotScanJob, delayedJobs, clock,
                "w" + properties.getSnowflake().getWorkerId(),
                new TransactionTemplate(txManager),
                machine);
    }

    public JobRunner(
            SlotGenerateJob slotGenerateJob,
            SlotScanJob slotScanJob,
            DelayedJobStore delayedJobs,
            AppClock clock,
            String instanceId,
            TransactionTemplate tx
    ) {
        this(slotGenerateJob, slotScanJob, delayedJobs, clock, instanceId, tx, null);
    }

    public JobRunner(
            SlotGenerateJob slotGenerateJob,
            SlotScanJob slotScanJob,
            DelayedJobStore delayedJobs,
            AppClock clock,
            String instanceId,
            TransactionTemplate tx,
            OrderStateMachine machine
    ) {
        this.slotGenerateJob = slotGenerateJob;
        this.slotScanJob = slotScanJob;
        this.delayedJobs = delayedJobs;
        this.occupyStore = delayedJobs instanceof SlotOccupyStore store ? store : null;
        this.machine = machine;
        this.clock = clock;
        this.instanceId = instanceId;
        this.tx = tx;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("JobRunner starting SlotGenerateJob (first-run backfill if window empty)");
        slotGenerateJob.run();
    }

    @Scheduled(cron = "0 15 2 * * *", zone = "Asia/Shanghai")
    public void dailyGenerateAt0215Shanghai() {
        slotGenerateJob.run();
    }

    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Shanghai")
    public void scanExpiredLocksEvery5Min() {
        slotScanJob.run();
    }

    @Scheduled(cron = "*/10 * * * * *", zone = "Asia/Shanghai")
    public void drainDelayedJobs() {
        drainDueJobs();
    }

    public int drainDueJobs() {
        List<Long> ids = claimDueJobs();
        int n = 0;
        for (long id : ids) {
            DelayedJobRow job = null;
            try {
                job = delayedJobs.findJob(id);
                if (job == null) {
                    continue;
                }
                completeJob(job, dispatch(job), null);
                n++;
            } catch (ApiException ex) {
                n += completeCaught(job, id, ex.getCode(), ex.getMessage());
            } catch (RuntimeException ex) {
                log.warn("JobRunner job={} failed: {}", id, ex.toString());
                n += completeCaught(job, id, ErrorCodes.INTERNAL, errorText(ex));
            }
        }
        return n;
    }

    private int completeCaught(DelayedJobRow job, long id, int code, String lastError) {
        if (job == null) {
            log.warn("JobRunner job={} failed before load, code={}", id, code);
            return 0;
        }
        try {
            completeJob(job, code, lastError);
        } catch (RuntimeException ex) {
            log.warn("JobRunner complete job={} failed: {}", id, ex.toString());
        }
        return 1;
    }

    static String errorText(Throwable ex) {
        String msg = ex.getMessage();
        return msg == null || msg.isBlank() ? ex.getClass().getSimpleName() : msg;
    }

    public List<Long> claimDueJobs() {
        if (tx == null) {
            return delayedJobs.claimDueJobs(instanceId, clock.now(), LEASE_SECONDS, CLAIM_LIMIT);
        }
        return tx.execute(status ->
                delayedJobs.claimDueJobs(instanceId, clock.now(), LEASE_SECONDS, CLAIM_LIMIT));
    }

    /**
     * D16: {@code 0} and {@code 40904} are success. Never mark 40904 FAILED.
     */
    public void completeJob(DelayedJobRow job, int fireResultCode, String lastError) {
        if (isJobSuccess(fireResultCode)) {
            delayedJobs.completeJob(job.id(), "DONE", null, clock.now());
            return;
        }
        delayedJobs.completeJob(job.id(), "FAILED", lastError, clock.now());
    }

    public static boolean isJobSuccess(int fireResultCode) {
        return fireResultCode == ErrorCodes.OK || fireResultCode == ErrorCodes.ILLEGAL_TRANSITION;
    }

    /**
     * Law A: only {@code fire()}. Never {@code ReleaseLock} first.
     * {@code BOOKED + PAY_TIMEOUT} → 40904, treated as DONE by {@link #completeJob}.
     */
    int dispatch(DelayedJobRow job) {
        if (machine == null) {
            return ErrorCodes.OK;
        }
        if (SlotOccupyService.JOB_RELEASE_LOCK.equals(job.jobType())) {
            return fireOrder(job, OrderEvent.PAY_TIMEOUT);
        }
        if (SlotOccupyService.JOB_RELEASE_ADDON.equals(job.jobType())) {
            return fireOrder(job, OrderEvent.ADD_ON_PAY_TIMEOUT);
        }
        return ErrorCodes.OK;
    }

    private int fireOrder(DelayedJobRow job, OrderEvent event) {
        Long orderId = resolveOrderId(job);
        if (orderId == null) {
            return ErrorCodes.OK;
        }
        machine.fire(orderId, event, FireContext.job());
        return ErrorCodes.OK;
    }

    Long resolveOrderId(DelayedJobRow job) {
        Long fromPayload = parseOrderId(job.payload());
        if (fromPayload != null) {
            return fromPayload;
        }
        Long holdId = parseHoldId(job.bizKey());
        if (holdId == null || occupyStore == null) {
            return null;
        }
        var byHold = occupyStore.findOrderByHoldId(holdId);
        if (byHold != null) {
            return byHold.id();
        }
        var byAddon = occupyStore.findOrderByAddOnHoldId(holdId);
        return byAddon == null ? null : byAddon.id();
    }

    public static Long parseOrderId(String payload) {
        return parseJsonLong(payload, "orderId");
    }

    public static Long parseHoldId(String bizKey) {
        if (bizKey == null || !bizKey.startsWith("hold:")) {
            return null;
        }
        try {
            return Long.parseLong(bizKey.substring("hold:".length()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static Long parseJsonLong(String json, String field) {
        if (json == null || json.isBlank() || field == null) {
            return null;
        }
        String key = "\"" + field + "\"";
        int at = json.indexOf(key);
        if (at < 0) {
            return null;
        }
        int colon = json.indexOf(':', at + key.length());
        if (colon < 0) {
            return null;
        }
        int i = colon + 1;
        while (i < json.length() && (json.charAt(i) == ' ' || json.charAt(i) == '"')) {
            i++;
        }
        int j = i;
        if (j < json.length() && json.charAt(j) == '-') {
            j++;
        }
        while (j < json.length() && Character.isDigit(json.charAt(j))) {
            j++;
        }
        if (j == i || (j == i + 1 && json.charAt(i) == '-')) {
            return null;
        }
        try {
            return Long.parseLong(json.substring(i, j));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
