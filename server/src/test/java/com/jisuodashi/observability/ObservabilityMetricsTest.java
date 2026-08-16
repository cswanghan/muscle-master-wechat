package com.jisuodashi.observability;

import com.jisuodashi.auth.HumanTask;
import com.jisuodashi.catalog.InMemoryCatalogRepository;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.inventory.AvailabilityCache;
import com.jisuodashi.inventory.AvailabilityDay;
import com.jisuodashi.inventory.InMemorySlotOccupyStore;
import com.jisuodashi.inventory.InMemoryTherapistDayLock;
import com.jisuodashi.inventory.OccupyFixtures;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.job.SlotScanJob;
import com.jisuodashi.payment.InMemoryPaymentStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 设计 §Observability 指标表的落地校验：7 个指标各自能被读出正确的值，
 * 且热表 gauge 走 60s 节流（禁止 15s 抓取直接打库）。
 */
class ObservabilityMetricsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 14);
    /** 门店营业 10:00–22:00，19:00 已开门 9 小时，满足"静默 2 小时"的判定前提。 */
    private static final Instant AT_1900 =
            TODAY.atTime(19, 0).atZone(AppClock.SHANGHAI).toInstant();

    // ---------- pay.success.rate ----------

    @Test
    void payRateIsOneWithoutTrafficAndDropsWithFailures() {
        TickClock clock = new TickClock(AT_1900);
        PayOutcomeMetrics metrics = new PayOutcomeMetrics(new AppClock(clock));

        assertThat(metrics.rate()).isEqualTo(1.0d);

        metrics.success();
        metrics.success();
        metrics.success();
        metrics.failure();
        assertThat(metrics.rate()).isEqualTo(0.75d);
    }

    @Test
    void payRateRollsOffAfterFiveMinutes() {
        TickClock clock = new TickClock(AT_1900);
        PayOutcomeMetrics metrics = new PayOutcomeMetrics(new AppClock(clock));
        metrics.failure();
        assertThat(metrics.rate()).isEqualTo(0.0d);

        clock.plus(Duration.ofMinutes(4));
        assertThat(metrics.rate()).isEqualTo(0.0d);

        clock.plus(Duration.ofMinutes(1));
        assertThat(metrics.rate()).isEqualTo(1.0d);
    }

    @Test
    void payRateIsRegisteredAsGauge() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        PayOutcomeMetrics metrics =
                new PayOutcomeMetrics(new AppClock(new TickClock(AT_1900)), meters);
        metrics.success();
        metrics.failure();
        assertThat(meters.get(PayOutcomeMetrics.METRIC).gauge().value()).isEqualTo(0.5d);
    }

    // ---------- 60s 刮取节流 ----------

    @Test
    void scrapedGaugeHitsSourceAtMostOncePerMinute() {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong nowMs = new AtomicLong(1_000L);
        ScrapedGauge gauge = new ScrapedGauge(calls::incrementAndGet, nowMs::get);

        assertThat(gauge.read()).isEqualTo(1.0d);
        // 模拟 Prometheus 15s 抓取：接下来三次都吃缓存。
        for (int i = 0; i < 3; i++) {
            nowMs.addAndGet(15_000L);
            assertThat(gauge.read()).isEqualTo(1.0d);
        }
        assertThat(calls.get()).isEqualTo(1);

        nowMs.addAndGet(15_001L);
        assertThat(gauge.read()).isEqualTo(2.0d);
        assertThat(calls.get()).isEqualTo(2);
    }

    // ---------- slot.locked.stale / store.order.silence / workflow.manual.open ----------

    @Test
    void businessMetricsScrapesStaleLocksSilentStoresAndOpenTasks() {
        InMemorySlotOccupyStore slots = OccupyFixtures.demoStore();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AppClock clock = new AppClock(new TickClock(AT_1900));
        SlotOccupyService occupy = new SlotOccupyService(
                slots, new InMemoryTherapistDayLock(), ids(), clock, meters);

        InMemoryPaymentStore payments = new InMemoryPaymentStore();
        payments.insertHumanTask(task(1L, "OPEN"));
        payments.insertHumanTask(task(2L, "OPEN"));
        payments.insertHumanTask(task(3L, "CLOSED"));

        BusinessMetrics business = new BusinessMetrics(
                slots, payments, new InMemoryCatalogRepository(),
                new AvailabilityCache(Duration.ofSeconds(30), clock.clock()),
                new ReleaseScanHeartbeat(clock), clock, meters);

        // 三家演示门店都开着门且一单未出 → 全静默（旗舰店 / 二分店 / 未开放门店）。
        business.scrapeAll();
        assertThat(business.silentStores()).isEqualTo(3.0d);
        assertThat(business.staleLocks()).isEqualTo(0.0d);
        assertThat(business.manualOpenTasks()).isEqualTo(2.0d);

        // 旗舰店出一单，静默数降到 2；把 hold 过期 40 分钟，卡死锁被刮出来。
        var locked = occupy.lockNew(OccupyFixtures.cmd("obs-1", OccupyFixtures.T1, OccupyFixtures.START_1930));
        slots.expireHold(locked.holdId(), TODAY.atTime(18, 20));

        business.scrapeAll();
        assertThat(business.silentStores()).isEqualTo(2.0d);
        assertThat(business.staleLocks()).isEqualTo(10.0d);

        assertThat(meters.get(BusinessMetrics.SLOT_LOCKED_STALE).gauge().value()).isEqualTo(10.0d);
        assertThat(meters.get(BusinessMetrics.STORE_ORDER_SILENCE).gauge().value()).isEqualTo(2.0d);
        assertThat(meters.get(BusinessMetrics.WORKFLOW_MANUAL_OPEN).gauge().value()).isEqualTo(2.0d);
    }

    @Test
    void closedStoreIsNotCountedAsSilent() {
        // 打烊（02:00）以及刚开门不足 2 小时（11:00）都不该报静默。
        assertThat(BusinessMetrics.minutesSinceOpen(
                LocalTime.of(10, 0), LocalTime.of(22, 0), LocalTime.of(2, 0))).isEqualTo(-1);
        assertThat(BusinessMetrics.minutesSinceOpen(
                LocalTime.of(10, 0), LocalTime.of(22, 0), LocalTime.of(11, 0))).isEqualTo(60);
        // 跨零点营业 20:00–02:00：01:00 已开门 5 小时。
        assertThat(BusinessMetrics.minutesSinceOpen(
                LocalTime.of(20, 0), LocalTime.of(2, 0), LocalTime.of(1, 0))).isEqualTo(300);
    }

    // ---------- slot.lock.fail{reason} ----------

    @Test
    void lockFailureIsCountedByReason() {
        InMemorySlotOccupyStore slots = OccupyFixtures.demoStore();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        SlotOccupyService occupy = new SlotOccupyService(
                slots, new InMemoryTherapistDayLock(), ids(),
                new AppClock(new TickClock(AT_1900)), meters);

        occupy.lockNew(OccupyFixtures.cmd("fail-1", OccupyFixtures.T1, OccupyFixtures.START_1930));
        assertThatThrownBy(() -> occupy.lockNew(
                OccupyFixtures.cmd("fail-2", OccupyFixtures.T1, OccupyFixtures.START_1930)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCodes.SLOT_UNAVAILABLE));

        assertThat(meters.counter(
                SlotOccupyService.METRIC_LOCK_FAIL, "reason", SlotOccupyService.REASON_SLOT_NOT_FREE)
                .count()).isEqualTo(1.0d);
    }

    // ---------- availability.cache.hit ----------

    @Test
    void cacheHitRatioCountsHitsOverTotal() {
        AppClock clock = new AppClock(new TickClock(AT_1900));
        AvailabilityCache cache = new AvailabilityCache(Duration.ofSeconds(30), clock.clock());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        BusinessMetrics business = new BusinessMetrics(
                OccupyFixtures.demoStore(), new InMemoryPaymentStore(),
                new InMemoryCatalogRepository(), cache,
                new ReleaseScanHeartbeat(clock), clock, meters);

        assertThat(business.cacheHitRatio()).isEqualTo(0.0d);

        cache.get(OccupyFixtures.STORE, TODAY, () -> emptyDay(TODAY));   // miss
        cache.get(OccupyFixtures.STORE, TODAY, () -> emptyDay(TODAY));   // hit
        cache.get(OccupyFixtures.STORE, TODAY, () -> emptyDay(TODAY));   // hit
        cache.get(OccupyFixtures.STORE, TODAY.plusDays(1), () -> emptyDay(TODAY.plusDays(1))); // miss

        assertThat(business.cacheHitRatio()).isEqualTo(0.5d);
        assertThat(meters.get(BusinessMetrics.AVAILABILITY_CACHE_HIT).gauge().value()).isEqualTo(0.5d);
    }

    // ---------- job.release.lag.ms ----------

    @Test
    void releaseLagGrowsUntilScanRuns() {
        TickClock clock = new TickClock(AT_1900);
        AppClock appClock = new AppClock(clock);
        ReleaseScanHeartbeat heartbeat = new ReleaseScanHeartbeat(appClock);

        clock.plus(Duration.ofMinutes(7));
        assertThat(heartbeat.lagMs()).isEqualTo(420_000L);

        InMemorySlotOccupyStore slots = OccupyFixtures.demoStore();
        SlotScanJob job = new SlotScanJob(
                new SlotOccupyService(slots, new InMemoryTherapistDayLock(), ids(), appClock));
        job.setHeartbeat(heartbeat);
        job.run();
        assertThat(heartbeat.lagMs()).isEqualTo(0L);

        clock.plus(Duration.ofMinutes(1));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        BusinessMetrics business = new BusinessMetrics(
                slots, new InMemoryPaymentStore(), new InMemoryCatalogRepository(),
                new AvailabilityCache(Duration.ofSeconds(30), clock),
                heartbeat, appClock, meters);
        assertThat(business.releaseLagMs()).isEqualTo(60_000L);
        assertThat(meters.get(BusinessMetrics.JOB_RELEASE_LAG_MS).gauge().value()).isEqualTo(60_000.0d);
    }

    // ---------- helpers ----------

    private static java.util.function.LongSupplier ids() {
        return new AtomicLong(9_100_000_000_000_000_000L)::incrementAndGet;
    }

    private static AvailabilityDay emptyDay(LocalDate date) {
        return new AvailabilityDay(OccupyFixtures.STORE, date, List.of(), List.of(), List.of());
    }

    private static HumanTask task(long id, String status) {
        HumanTask t = new HumanTask();
        t.setId(id);
        t.setTaskType("ABNORMAL_ORDER");
        t.setBizKey("obs:" + id);
        t.setTitle("异常单 " + id);
        t.setStatus(status);
        t.setCreatedAt(AT_1900);
        return t;
    }

    /** 可推进的测试时钟；{@link AppClock} 只认 {@link Clock}。 */
    private static final class TickClock extends Clock {

        private Instant now;

        private TickClock(Instant start) {
            this.now = start;
        }

        void plus(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return AppClock.SHANGHAI;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
