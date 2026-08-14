package com.jisuodashi.observability;

import com.jisuodashi.catalog.CatalogModels;
import com.jisuodashi.catalog.CatalogRepository;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.inventory.AvailabilityCache;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.inventory.SlotOccupyStore;
import com.jisuodashi.payment.PaymentStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 设计 §Observability 指标表里的"刮取型" gauge。业务告警优先于 CPU：
 * 打热表的三个（{@code slot.locked.stale} / {@code store.order.silence} /
 * {@code workflow.manual.open}）统一走 {@link ScrapedGauge} 的 60s 节流；
 * 缓存命中率和任务滞后是内存读，直接算。
 *
 * <p>计数器型指标留在产生它们的地方：{@code slot.lock.fail} / {@code slot.locked.stale_paid}
 * 在 {@link SlotOccupyService}，{@code inventory.drift} 在 {@code InventoryDriftGauge}。
 */
@Component
public class BusinessMetrics {

    public static final String SLOT_LOCKED_STALE = "slot.locked.stale";
    public static final String STORE_ORDER_SILENCE = "store.order.silence";
    public static final String WORKFLOW_MANUAL_OPEN = "workflow.manual.open";
    public static final String AVAILABILITY_CACHE_HIT = "availability.cache.hit";
    public static final String JOB_RELEASE_LAG_MS = "job.release.lag.ms";

    /** 营业中门店连续多久没有新单算"静默"。 */
    public static final int SILENCE_HOURS = 2;

    private final SlotOccupyStore slots;
    private final PaymentStore payments;
    private final CatalogRepository catalog;
    private final AvailabilityCache cache;
    private final ReleaseScanHeartbeat heartbeat;
    private final AppClock clock;

    private final ScrapedGauge staleLocks;
    private final ScrapedGauge silentStores;
    private final ScrapedGauge manualOpen;

    @Autowired
    public BusinessMetrics(
            SlotOccupyStore slots,
            PaymentStore payments,
            CatalogRepository catalog,
            AvailabilityCache cache,
            ReleaseScanHeartbeat heartbeat,
            AppClock clock,
            @Autowired(required = false) MeterRegistry meters) {
        this.slots = slots;
        this.payments = payments;
        this.catalog = catalog;
        this.cache = cache;
        this.heartbeat = heartbeat;
        this.clock = clock;
        java.util.function.LongSupplier nowMs = () -> clock.instant().toEpochMilli();
        this.staleLocks = new ScrapedGauge(this::readStaleLocks, nowMs);
        this.silentStores = new ScrapedGauge(this::readSilentStores, nowMs);
        this.manualOpen = new ScrapedGauge(this::readManualOpen, nowMs);
        if (meters != null) {
            Gauge.builder(SLOT_LOCKED_STALE, staleLocks, ScrapedGauge::read)
                    .description("LOCKED slots whose lock_expire_at is older than 30 minutes")
                    .register(meters);
            Gauge.builder(STORE_ORDER_SILENCE, silentStores, ScrapedGauge::read)
                    .description("Open stores with no new order in the last 2 hours")
                    .register(meters);
            Gauge.builder(WORKFLOW_MANUAL_OPEN, manualOpen, ScrapedGauge::read)
                    .description("human_task rows still OPEN")
                    .register(meters);
            Gauge.builder(AVAILABILITY_CACHE_HIT, this, BusinessMetrics::cacheHitRatio)
                    .description("Availability cache hits / (hits + misses)")
                    .register(meters);
            Gauge.builder(JOB_RELEASE_LAG_MS, heartbeat, h -> (double) h.lagMs())
                    .description("Milliseconds since the last release scan finished")
                    .register(meters);
        }
    }

    /** 强制刮取三个热表 gauge（运维核对 / 测试用）。 */
    public void scrapeAll() {
        staleLocks.scrape();
        silentStores.scrape();
        manualOpen.scrape();
    }

    public double staleLocks() {
        return staleLocks.read();
    }

    public double silentStores() {
        return silentStores.read();
    }

    public double manualOpenTasks() {
        return manualOpen.read();
    }

    public double releaseLagMs() {
        return heartbeat.lagMs();
    }

    public double cacheHitRatio() {
        long hits = cache.hits();
        long total = hits + cache.misses();
        return total == 0 ? 0.0d : (double) hits / total;
    }

    private double readStaleLocks() {
        return slots.countLockedExpiredBefore(
                clock.now().minusMinutes(SlotOccupyService.STUCK_LOCK_MINUTES));
    }

    private double readManualOpen() {
        return payments.countOpenHumanTasks();
    }

    private double readSilentStores() {
        LocalDateTime now = clock.now();
        LocalDateTime since = now.minusHours(SILENCE_HOURS);
        int silent = 0;
        for (CatalogModels.Store store : catalog.listStores()) {
            if (store.status() != 1) {
                continue;
            }
            long openMinutes = minutesSinceOpen(
                    store.businessStart(), store.businessEnd(), now.toLocalTime());
            if (openMinutes < SILENCE_HOURS * 60L) {
                continue;
            }
            if (slots.countOrdersCreatedSince(store.id(), since) == 0) {
                silent++;
            }
        }
        return silent;
    }

    /** 分钟数；已打烊或还没到营业时间返回 -1。跨零点的营业时间（如 10:00–02:00）按环形算。 */
    static long minutesSinceOpen(LocalTime start, LocalTime end, LocalTime now) {
        if (start == null || end == null) {
            return -1;
        }
        int day = 24 * 60 * 60;
        int s = start.toSecondOfDay();
        int e = end.toSecondOfDay();
        int n = now.toSecondOfDay();
        int span = e > s ? e - s : e + day - s;
        int since = n >= s ? n - s : n + day - s;
        return since <= span ? since / 60 : -1;
    }
}
