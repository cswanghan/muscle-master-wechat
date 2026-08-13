package com.jisuodashi.job;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.inventory.DelayedJobStore;
import com.jisuodashi.inventory.DelayedJobStore.DelayedJobRow;
import com.jisuodashi.inventory.SlotOccupyService;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single runner (D16). Gated by {@code app.jobs.enabled}.
 * Claim: {@code PENDING ∧ run_at<=now} or {@code RUNNING ∧ lease_until<now}.
 * {@code 40904} is DONE. RELEASE_LOCK only {@code fire(PAY_TIMEOUT)} (Law A).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.jobs", name = "enabled", havingValue = "true")
public class JobRunner {

    public static final int LEASE_SECONDS = 60;
    public static final int CLAIM_LIMIT = 50;

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);
    private static final Pattern ORDER_ID = Pattern.compile("\"orderId\"\\s*:\\s*(\\d+)");

    private final SlotGenerateJob slotGenerateJob;
    private final SlotScanJob slotScanJob;
    private final DelayedJobStore delayedJobs;
    private final AppClock clock;
    private final String instanceId;
    private final TransactionTemplate tx;
    private final OrderStateMachine machine;

    public JobRunner(
            SlotGenerateJob slotGenerateJob,
            SlotScanJob slotScanJob,
            DelayedJobStore delayedJobs,
            AppClock clock,
            AppProperties properties,
            PlatformTransactionManager txManager,
            @Autowired(required = false) OrderStateMachine machine
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
        this.clock = clock;
        this.instanceId = instanceId;
        this.tx = tx;
        this.machine = machine;
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
            DelayedJobRow job = delayedJobs.findJob(id);
            if (job == null) {
                continue;
            }
            int code = dispatch(job);
            completeJob(job, code, null);
            n++;
        }
        return n;
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
     * RELEASE_LOCK only {@code fire(PAY_TIMEOUT)}. Already-paid → 40904 → DONE (D25).
     */
    public int dispatch(DelayedJobRow job) {
        if (SlotOccupyService.JOB_RELEASE_LOCK.equals(job.jobType())) {
            return firePayTimeout(job);
        }
        if (SlotOccupyService.JOB_RELEASE_ADDON.equals(job.jobType())) {
            return ErrorCodes.OK;
        }
        return ErrorCodes.OK;
    }

    private int firePayTimeout(DelayedJobRow job) {
        if (machine == null) {
            return ErrorCodes.OK;
        }
        Long orderId = orderIdFromPayload(job.payload());
        if (orderId == null) {
            log.warn("RELEASE_LOCK missing orderId job={}", job.id());
            return ErrorCodes.OK;
        }
        try {
            machine.fire(orderId, OrderEvent.PAY_TIMEOUT, FireContext.job());
            return ErrorCodes.OK;
        } catch (ApiException ex) {
            return ex.getCode();
        }
    }

    static Long orderIdFromPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        Matcher m = ORDER_ID.matcher(payload);
        if (!m.find()) {
            return null;
        }
        return Long.parseLong(m.group(1));
    }
}
