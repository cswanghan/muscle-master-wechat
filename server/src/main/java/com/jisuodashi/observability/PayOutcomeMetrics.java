package com.jisuodashi.observability;

import com.jisuodashi.common.AppClock;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@code pay.success.rate} = success / (success + fail)，滚动 5 分钟（5 个 1 分钟桶）。
 * 无流量时返回 1.0，免得半夜没人下单也告警。
 */
@Component
public class PayOutcomeMetrics {

    public static final String METRIC = "pay.success.rate";
    public static final int WINDOW_MINUTES = 5;

    private final AppClock clock;
    private final long[] minute = new long[WINDOW_MINUTES];
    private final int[] success = new int[WINDOW_MINUTES];
    private final int[] fail = new int[WINDOW_MINUTES];

    @Autowired
    public PayOutcomeMetrics(AppClock clock, @Autowired(required = false) MeterRegistry meters) {
        this.clock = clock;
        java.util.Arrays.fill(minute, Long.MIN_VALUE);
        if (meters != null) {
            Gauge.builder(METRIC, this, PayOutcomeMetrics::rate)
                    .description("WeChat/cash payment success ratio over the last 5 minutes")
                    .register(meters);
        }
    }

    public PayOutcomeMetrics(AppClock clock) {
        this(clock, null);
    }

    /** 支付落 SUCCESS（微信回调金额匹配 / 现金收银）。 */
    public void success() {
        record(true);
    }

    /** 支付失败：金额不符、预下单失败、回调找不到单。 */
    public void failure() {
        record(false);
    }

    public synchronized double rate() {
        long nowMinute = nowMinute();
        long oldest = nowMinute - WINDOW_MINUTES + 1;
        long ok = 0;
        long bad = 0;
        for (int i = 0; i < WINDOW_MINUTES; i++) {
            if (minute[i] < oldest || minute[i] > nowMinute) {
                continue;
            }
            ok += success[i];
            bad += fail[i];
        }
        long total = ok + bad;
        return total == 0 ? 1.0d : (double) ok / total;
    }

    private synchronized void record(boolean ok) {
        long nowMinute = nowMinute();
        int idx = (int) Math.floorMod(nowMinute, (long) WINDOW_MINUTES);
        if (minute[idx] != nowMinute) {
            minute[idx] = nowMinute;
            success[idx] = 0;
            fail[idx] = 0;
        }
        if (ok) {
            success[idx]++;
        } else {
            fail[idx]++;
        }
    }

    private long nowMinute() {
        return clock.instant().toEpochMilli() / 60_000L;
    }
}
