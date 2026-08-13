package com.jisuodashi.inventory;

import com.jisuodashi.common.AppClock;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Occupancy vs LOCKED/BOOKED/BUFFER mismatch. Scraped at most every 60s. */
@Component
public class InventoryDriftGauge {

    public static final String METRIC = "inventory.drift";
    public static final long SCRAPE_MS = 60_000L;

    private final SlotOccupyStore store;
    private final AppClock clock;
    private final AtomicInteger value = new AtomicInteger(0);
    private final AtomicLong lastScrapeMs = new AtomicLong(0);

    @Autowired
    public InventoryDriftGauge(
            SlotOccupyStore store,
            AppClock clock,
            @Autowired(required = false) MeterRegistry meters) {
        this.store = store;
        this.clock = clock;
        if (meters != null) {
            Gauge.builder(METRIC, this, InventoryDriftGauge::read)
                    .description("Occupancy XOR LOCKED/BOOKED/BUFFER mismatch")
                    .register(meters);
        }
    }

    public double read() {
        scrapeIfDue();
        return value.get();
    }

    public int scrape() {
        int n = store.countInventoryDrift();
        value.set(n);
        lastScrapeMs.set(clock.instant().toEpochMilli());
        return n;
    }

    private void scrapeIfDue() {
        long now = clock.instant().toEpochMilli();
        long last = lastScrapeMs.get();
        if (last != 0 && now - last < SCRAPE_MS) {
            return;
        }
        if (lastScrapeMs.compareAndSet(last, now)) {
            value.set(store.countInventoryDrift());
        }
    }
}
