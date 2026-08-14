package com.jisuodashi.observability;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/**
 * DB 刮取型 gauge 的取值缓存。设计 §Observability：热表指标 <b>每 60s</b> 刮一次，
 * 不允许 Prometheus 的 15s 抓取直接打到 {@code therapist_slot} / {@code booking_order}。
 */
public final class ScrapedGauge {

    public static final long SCRAPE_MS = 60_000L;

    private final DoubleSupplier source;
    private final LongSupplier nowMs;
    private final long scrapeMs;
    private final AtomicLong lastScrapeMs = new AtomicLong(0);
    private volatile double value;

    public ScrapedGauge(DoubleSupplier source, LongSupplier nowMs) {
        this(source, nowMs, SCRAPE_MS);
    }

    public ScrapedGauge(DoubleSupplier source, LongSupplier nowMs, long scrapeMs) {
        this.source = source;
        this.nowMs = nowMs;
        this.scrapeMs = scrapeMs;
    }

    /** Micrometer 抓取入口：距上次刮取不足 {@link #SCRAPE_MS} 时返回缓存值。 */
    public double read() {
        long now = nowMs.getAsLong();
        long last = lastScrapeMs.get();
        if (last != 0 && now - last < scrapeMs) {
            return value;
        }
        if (lastScrapeMs.compareAndSet(last, now)) {
            value = source.getAsDouble();
        }
        return value;
    }

    /** 强制刮取（测试/运维手动核对用）。 */
    public double scrape() {
        double v = source.getAsDouble();
        value = v;
        lastScrapeMs.set(nowMs.getAsLong());
        return v;
    }

    public double cached() {
        return value;
    }
}
