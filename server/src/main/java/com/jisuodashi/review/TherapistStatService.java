package com.jisuodashi.review;

import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.TherapistStatsPort;
import com.jisuodashi.common.TherapistStatsView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 30 天统计的读写门面：读侧批量取，写侧日更全量 + 评价增量。 */
@Service
public class TherapistStatService implements TherapistStatsPort {

    private static final Logger log = LoggerFactory.getLogger(TherapistStatService.class);

    private final TherapistStatStore stats;
    private final ReviewStore reviews;
    private final ServedVisitSource visits;
    private final AppClock clock;

    @Autowired
    public TherapistStatService(
            TherapistStatStore stats,
            ReviewStore reviews,
            ServedVisitSource visits,
            AppClock clock) {
        this.stats = stats;
        this.reviews = reviews;
        this.visits = visits;
        this.clock = clock;
    }

    /** 缺行返回 {@link TherapistStatsView#NONE}（新技师），不返回 null。 */
    @Override
    public Map<Long, TherapistStatsView> statsFor(Collection<Long> therapistIds) {
        if (therapistIds == null || therapistIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, TherapistStat> rows = stats.findAll(therapistIds);
        return therapistIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(java.util.stream.Collectors.toMap(
                        id -> id,
                        id -> view(rows.get(id))));
    }

    public TherapistStatsView statsFor(long therapistId) {
        return statsFor(List.of(therapistId)).get(therapistId);
    }

    /** 样本不足时藏掉的是**率**不是数：小样本只有比率会失真，绝对条数不会。 */
    static TherapistStatsView view(TherapistStat stat) {
        if (stat == null) {
            return TherapistStatsView.NONE;
        }
        boolean enough = stat.reviewCount() >= ReviewPolicy.MIN_REVIEWS_FOR_RATE;
        return new TherapistStatsView(
                stat.servedCount(),
                stat.repeatCount(),
                stat.repeatCustomerCount(),
                stat.reviewCount(),
                enough ? stat.positiveRateX100() : null,
                enough ? stat.avgScoreX100() : null,
                !enough);
    }

    /** 日更 job 入口：全量重算并整表替换。 */
    public int recomputeAll() {
        LocalDate end = clock.today();
        LocalDate start = end.minusDays(ReviewPolicy.STAT_WINDOW_DAYS);
        Instant startAt = start.atStartOfDay(AppClock.SHANGHAI).toInstant();
        Instant now = clock.instant();

        List<TherapistStat> computed = TherapistStatCalculator.compute(
                visits.listCompletedVisits(),
                reviews.listSince(startAt),
                start,
                end,
                startAt,
                now);
        stats.replaceAll(computed);
        log.info("therapist_stat_30d recomputed rows={} window={}..{}", computed.size(), start, end);
        return computed.size();
    }

    /** 评价落库后调用；失败不回滚评价 —— 统计是派生数据，日更会补齐。 */
    public void onReviewed(long therapistId, int score, boolean positive) {
        try {
            stats.bumpReview(therapistId, score, positive, clock.instant());
        } catch (RuntimeException e) {
            log.warn("therapist_stat_30d bump failed therapistId={} — 等日更重算", therapistId, e);
        }
    }
}
