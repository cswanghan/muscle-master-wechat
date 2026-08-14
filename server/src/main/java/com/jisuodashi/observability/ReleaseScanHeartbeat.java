package com.jisuodashi.observability;

import com.jisuodashi.common.AppClock;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code job.release.lag.ms}：距上一次释放扫描（5 min cron）结束的毫秒数。
 * 启动即打一次心跳，所以"扫描根本没跑起来"和"扫描卡住"一样会让 lag 涨上去。
 */
@Component
public class ReleaseScanHeartbeat {

    private final AppClock clock;
    private final AtomicLong lastRunMs;

    public ReleaseScanHeartbeat(AppClock clock) {
        this.clock = clock;
        this.lastRunMs = new AtomicLong(clock.instant().toEpochMilli());
    }

    public void mark() {
        lastRunMs.set(clock.instant().toEpochMilli());
    }

    public long lagMs() {
        return Math.max(0L, clock.instant().toEpochMilli() - lastRunMs.get());
    }
}
