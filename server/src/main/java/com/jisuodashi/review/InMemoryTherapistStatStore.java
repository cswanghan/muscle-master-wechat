package com.jisuodashi.review;

import com.jisuodashi.catalog.DemoCatalogIds;
import com.jisuodashi.common.AppClock;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * dev 侧统计表。带演示种子 —— dev 的 {@code app.jobs.enabled=false}，日更 job 不跑，
 * 不种子就是一屏零，小程序上没法看效果。周可刻意留在 5 条以下，用来演示冷启动兜底。
 */
@Repository
@Profile("dev")
public class InMemoryTherapistStatStore implements TherapistStatStore {

    private final Map<Long, TherapistStat> stats = new ConcurrentHashMap<>();
    private final AppClock clock;

    public InMemoryTherapistStatStore(AppClock clock) {
        this.clock = clock;
        seedDemo();
    }

    @Override
    public Map<Long, TherapistStat> findAll(Collection<Long> therapistIds) {
        Map<Long, TherapistStat> out = new LinkedHashMap<>();
        for (Long id : therapistIds) {
            TherapistStat stat = id == null ? null : stats.get(id);
            if (stat != null) {
                out.put(id, stat);
            }
        }
        return out;
    }

    @Override
    public void replaceAll(List<TherapistStat> next) {
        stats.clear();
        next.forEach(s -> stats.put(s.therapistId(), s));
    }

    @Override
    public synchronized void bumpReview(long therapistId, int score, boolean positive, Instant now) {
        LocalDate today = clock.today();
        TherapistStat cur = stats.get(therapistId);
        if (cur == null) {
            cur = TherapistStat.empty(
                    therapistId, today.minusDays(ReviewPolicy.STAT_WINDOW_DAYS), today, now);
        }
        int reviewCount = cur.reviewCount() + 1;
        int positiveCount = cur.positiveCount() + (positive ? 1 : 0);
        // 均分从 avg*count 反推，有小数点后的漂移；日更 job 全量重算会校正回来。
        int scoreSum = (int) Math.round(cur.avgScoreX100() * (long) cur.reviewCount() / 100.0) + score;
        stats.put(therapistId, new TherapistStat(
                therapistId,
                cur.servedCount(),
                cur.repeatCount(),
                cur.repeatCustomerCount(),
                reviewCount,
                positiveCount,
                TherapistStatCalculator.positiveRateX100(positiveCount, reviewCount),
                TherapistStatCalculator.avgScoreX100(scoreSum, reviewCount),
                cur.windowStart(),
                today,
                now));
    }

    private void seedDemo() {
        LocalDate end = clock.today();
        LocalDate start = end.minusDays(ReviewPolicy.STAT_WINDOW_DAYS);
        Instant at = clock.instant();
        seed(DemoCatalogIds.THERAPIST_LIN, 128, 76, 41, 52, 50, 478, start, end, at);
        seed(DemoCatalogIds.THERAPIST_CHEN, 86, 39, 24, 33, 30, 452, start, end, at);
        seed(DemoCatalogIds.THERAPIST_ZHOU, 21, 4, 3, 3, 3, 500, start, end, at);
    }

    private void seed(
            long therapistId, int served, int repeat, int repeatCustomers,
            int reviews, int positives, int avgScoreX100,
            LocalDate start, LocalDate end, Instant at) {
        stats.put(therapistId, new TherapistStat(
                therapistId, served, repeat, repeatCustomers, reviews, positives,
                TherapistStatCalculator.positiveRateX100(positives, reviews),
                avgScoreX100, start, end, at));
    }
}
