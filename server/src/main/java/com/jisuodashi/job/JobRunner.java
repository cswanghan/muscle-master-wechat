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
import com.jisuodashi.review.TherapistStatService;
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
 * {@code 40904} is DONE. RELEASE_LOCK / RELEASE_ADDON only {@code fire()} (Law A).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.jobs", name = "enabled", havingValue = "true")
public class JobRunner {

    public static final int LEASE_SECONDS = 60;
    public static final int CLAIM_LIMIT = 50;

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);
    private static final Pattern ORDER_ID = Pattern.compile("\"orderId\"\\s*:\\s*(\\d+)");
    private static final Pattern HOLD_BIZ = Pattern.compile("^hold:(\\d+)$");
    static final int LAST_ERROR_MAX = 512;

    private final SlotGenerateJob slotGenerateJob;
    private final SlotScanJob slotScanJob;
    private final DelayedJobStore delayedJobs;
    private final AppClock clock;
    private final String instanceId;
    private final TransactionTemplate tx;
    private final OrderStateMachine machine;
    private final TherapistStatService therapistStats;

    public JobRunner(
            SlotGenerateJob slotGenerateJob,
            SlotScanJob slotScanJob,
            DelayedJobStore delayedJobs,
            AppClock clock,
            AppProperties properties,
            PlatformTransactionManager txManager,
            @Autowired(required = false) OrderStateMachine machine,
            @Autowired(required = false) TherapistStatService therapistStats
    ) {
        this(slotGenerateJob, slotScanJob, delayedJobs, clock,
                "w" + properties.getSnowflake().getWorkerId(),
                new TransactionTemplate(txManager),
                machine,
                therapistStats);
    }

    public JobRunner(
            SlotGenerateJob slotGenerateJob,
            SlotScanJob slotScanJob,
            DelayedJobStore delayedJobs,
            AppClock clock,
            String instanceId,
            TransactionTemplate tx
    ) {
        this(slotGenerateJob, slotScanJob, delayedJobs, clock, instanceId, tx, null, null);
    }

    /** 统计缺席的重载：排班/清算类用例只关心 drain，不需要背上评价统计。 */
    public JobRunner(
            SlotGenerateJob slotGenerateJob,
            SlotScanJob slotScanJob,
            DelayedJobStore delayedJobs,
            AppClock clock,
            String instanceId,
            TransactionTemplate tx,
            OrderStateMachine machine
    ) {
        this(slotGenerateJob, slotScanJob, delayedJobs, clock, instanceId, tx, machine, null);
    }

    public JobRunner(
            SlotGenerateJob slotGenerateJob,
            SlotScanJob slotScanJob,
            DelayedJobStore delayedJobs,
            AppClock clock,
            String instanceId,
            TransactionTemplate tx,
            OrderStateMachine machine,
            TherapistStatService therapistStats
    ) {
        this.slotGenerateJob = slotGenerateJob;
        this.slotScanJob = slotScanJob;
        this.delayedJobs = delayedJobs;
        this.clock = clock;
        this.instanceId = instanceId;
        this.tx = tx;
        this.machine = machine;
        this.therapistStats = therapistStats;
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

    /**
     * 30 天统计全量重算。放在 02:40，排在 02:15 的 slot 生成之后，两者不抢同一批表。
     * 评价写入时已经增量刷过评价那半边，这里补的是「服务/回头」以及窗口滚出的旧数据。
     */
    @Scheduled(cron = "0 40 2 * * *", zone = "Asia/Shanghai")
    public void recomputeTherapistStatsAt0240Shanghai() {
        recomputeTherapistStats();
    }

    public int recomputeTherapistStats() {
        if (therapistStats == null) {
            return 0;
        }
        try {
            return therapistStats.recomputeAll();
        } catch (RuntimeException e) {
            log.warn("therapist_stat_30d recompute failed", e);
            return 0;
        }
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
            runClaimedJob(job);
            n++;
        }
        return n;
    }

    /**
     * Isolate one claimed row. {@code ApiException} (incl. 40904) completes;
     * any other failure is {@code FAILED} + {@code last_error} and the batch continues.
     */
    public int runClaimedJob(DelayedJobRow job) {
        try {
            int code = dispatch(job);
            completeJob(job, code, isJobSuccess(code) ? null : "code=" + code);
            return code;
        } catch (ApiException ex) {
            completeJob(job, ex.getCode(), ex.getMessage());
            return ex.getCode();
        } catch (RuntimeException ex) {
            log.warn("job {} {} failed", job.id(), job.jobType(), ex);
            completeJob(job, ErrorCodes.INTERNAL, ex.getMessage());
            return ErrorCodes.INTERNAL;
        }
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
        delayedJobs.completeJob(job.id(), "FAILED", trimError(lastError), clock.now());
    }

    public static boolean isJobSuccess(int fireResultCode) {
        return fireResultCode == ErrorCodes.OK || fireResultCode == ErrorCodes.ILLEGAL_TRANSITION;
    }

    /**
     * RELEASE_LOCK / RELEASE_ADDON only {@code fire()}. Already-paid timeout → 40904 → DONE (D25).
     */
    public int dispatch(DelayedJobRow job) {
        if (SlotOccupyService.JOB_RELEASE_LOCK.equals(job.jobType())) {
            return fireOrder(job, OrderEvent.PAY_TIMEOUT);
        }
        if (SlotOccupyService.JOB_RELEASE_ADDON.equals(job.jobType())) {
            return fireOrder(job, OrderEvent.ADD_ON_PAY_TIMEOUT);
        }
        return ErrorCodes.OK;
    }

    private int fireOrder(DelayedJobRow job, OrderEvent event) {
        if (machine == null) {
            return ErrorCodes.OK;
        }
        Long orderId = resolveOrderId(job);
        if (orderId == null) {
            log.warn("{} missing orderId job={} biz={}", job.jobType(), job.id(), job.bizKey());
            return ErrorCodes.INTERNAL;
        }
        try {
            machine.fire(orderId, event, FireContext.job());
            return ErrorCodes.OK;
        } catch (ApiException ex) {
            return ex.getCode();
        }
    }

    Long resolveOrderId(DelayedJobRow job) {
        Long fromPayload = orderIdFromPayload(job.payload());
        if (fromPayload != null) {
            return fromPayload;
        }
        Long holdId = holdIdFromBizKey(job.bizKey());
        if (holdId == null || !(delayedJobs instanceof SlotOccupyStore occupy)) {
            return null;
        }
        SlotOccupyStore.BookingOrderRef byHold = occupy.findOrderByHoldId(holdId);
        if (byHold != null) {
            return byHold.id();
        }
        SlotOccupyStore.BookingOrderRef byAddon = occupy.findOrderByAddOnHoldId(holdId);
        return byAddon == null ? null : byAddon.id();
    }

    public static Long parseOrderId(String payload) {
        return orderIdFromPayload(payload);
    }

    public static Long parseHoldId(String bizKey) {
        return holdIdFromBizKey(bizKey);
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

    static Long holdIdFromBizKey(String bizKey) {
        if (bizKey == null || bizKey.isBlank()) {
            return null;
        }
        Matcher m = HOLD_BIZ.matcher(bizKey);
        return m.matches() ? Long.parseLong(m.group(1)) : null;
    }

    static String trimError(String lastError) {
        if (lastError == null || lastError.isBlank()) {
            return lastError;
        }
        return lastError.length() <= LAST_ERROR_MAX ? lastError : lastError.substring(0, LAST_ERROR_MAX);
    }
}
