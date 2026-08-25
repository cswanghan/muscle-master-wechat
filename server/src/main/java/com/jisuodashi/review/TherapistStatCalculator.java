package com.jisuodashi.review;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 30 天统计的聚合逻辑，dev/prod 共用。纯函数，无 IO。
 *
 * <p>回头判定的坑：首访序号在**全历史**上算，再按窗口过滤。反过来先截窗口再排序，
 * 会把「窗口外首访、窗口内又来」的老客误判成首访，回头数系统性偏低。
 */
public final class TherapistStatCalculator {

    private TherapistStatCalculator() {
    }

    public static List<TherapistStat> compute(
            List<ServedVisitSource.ServedVisit> allVisits,
            List<Review> windowReviews,
            LocalDate windowStart,
            LocalDate windowEnd,
            Instant windowStartAt,
            Instant calcAt
    ) {
        Map<Long, Served> served = served(allVisits, windowStartAt);
        Map<Long, Reviewed> reviewed = reviewed(windowReviews);

        Set<Long> therapistIds = new HashSet<>(served.keySet());
        therapistIds.addAll(reviewed.keySet());

        List<TherapistStat> out = new ArrayList<>(therapistIds.size());
        for (long therapistId : therapistIds.stream().sorted().toList()) {
            Served s = served.getOrDefault(therapistId, Served.EMPTY);
            Reviewed r = reviewed.getOrDefault(therapistId, Reviewed.EMPTY);
            out.add(new TherapistStat(
                    therapistId,
                    s.total,
                    s.repeatVisits,
                    s.repeatCustomers.size(),
                    r.count,
                    r.positive,
                    positiveRateX100(r.positive, r.count),
                    avgScoreX100(r.scoreSum, r.count),
                    windowStart,
                    windowEnd,
                    calcAt));
        }
        return out;
    }

    /** 向下取整而不是四舍五入：99.6% 显示成「100%」是在替技师夸口，宁可少报。 */
    public static int positiveRateX100(int positiveCount, int reviewCount) {
        return reviewCount <= 0 ? 0 : (int) (positiveCount * 10000L / reviewCount);
    }

    public static int avgScoreX100(int scoreSum, int reviewCount) {
        return reviewCount <= 0 ? 0 : (int) ((scoreSum * 100L + reviewCount / 2) / reviewCount);
    }

    private static Map<Long, Served> served(
            List<ServedVisitSource.ServedVisit> allVisits, Instant windowStartAt) {
        Map<String, List<ServedVisitSource.ServedVisit>> byPair = new HashMap<>();
        for (ServedVisitSource.ServedVisit v : allVisits) {
            if (v.startedAt() == null) {
                continue;
            }
            byPair.computeIfAbsent(pairKey(v.therapistId(), v.customerId()), k -> new ArrayList<>()).add(v);
        }

        Map<Long, Served> out = new HashMap<>();
        for (List<ServedVisitSource.ServedVisit> visits : byPair.values()) {
            visits.sort(Comparator.comparing(ServedVisitSource.ServedVisit::startedAt));
            for (int rn = 0; rn < visits.size(); rn++) {
                ServedVisitSource.ServedVisit v = visits.get(rn);
                if (v.startedAt().isBefore(windowStartAt)) {
                    continue;
                }
                Served s = out.computeIfAbsent(v.therapistId(), k -> new Served());
                s.total++;
                if (rn > 0) {
                    s.repeatVisits++;
                    s.repeatCustomers.add(v.customerId());
                }
            }
        }
        return out;
    }

    /** 逻辑删除（{@code deleted_at}）在仓储层就滤掉了，这里拿到的都是有效评价。 */
    private static Map<Long, Reviewed> reviewed(List<Review> reviews) {
        Map<Long, Reviewed> out = new HashMap<>();
        for (Review review : reviews) {
            Reviewed r = out.computeIfAbsent(review.therapistId(), k -> new Reviewed());
            r.count++;
            r.scoreSum += review.score();
            if (ReviewPolicy.positive(review.score())) {
                r.positive++;
            }
        }
        return out;
    }

    /**
     * 两个 snowflake ID 拼成一个 key。Snowflake 是 63 位，拼不进一个 long，
     * 用 {@code hash} 会碰撞，所以退回字符串 key 由 HashMap 兜底。
     */
    private static String pairKey(long therapistId, long customerId) {
        return therapistId + ":" + customerId;
    }

    private static final class Served {
        static final Served EMPTY = new Served();
        int total;
        int repeatVisits;
        final Set<Long> repeatCustomers = new HashSet<>();
    }

    private static final class Reviewed {
        static final Reviewed EMPTY = new Reviewed();
        int count;
        int positive;
        int scoreSum;
    }
}
