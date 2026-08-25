package com.jisuodashi.review;

import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.JdbcTimes;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
@Profile("!dev")
public class JdbcTherapistStatStore implements TherapistStatStore {

    private static final String COLUMNS =
            "therapist_id, served_count, repeat_count, repeat_customer_count, "
                    + "review_count, positive_count, positive_rate_x100, avg_score_x100, "
                    + "window_start, window_end, calc_at";

    private static final RowMapper<TherapistStat> ROW = (rs, i) -> new TherapistStat(
            rs.getLong("therapist_id"),
            rs.getInt("served_count"),
            rs.getInt("repeat_count"),
            rs.getInt("repeat_customer_count"),
            rs.getInt("review_count"),
            rs.getInt("positive_count"),
            rs.getInt("positive_rate_x100"),
            rs.getInt("avg_score_x100"),
            rs.getObject("window_start", LocalDate.class),
            rs.getObject("window_end", LocalDate.class),
            JdbcTimes.instant(rs.getTimestamp("calc_at")));

    private final JdbcTemplate jdbc;
    private final AppClock clock;

    public JdbcTherapistStatStore(JdbcTemplate jdbc, AppClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Map<Long, TherapistStat> findAll(Collection<Long> therapistIds) {
        List<Long> ids = therapistIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", ids.stream().map(x -> "?").toList());
        List<TherapistStat> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM therapist_stat_30d WHERE therapist_id IN (" + placeholders + ")",
                ROW,
                ids.toArray());
        Map<Long, TherapistStat> out = new LinkedHashMap<>();
        rows.forEach(r -> out.put(r.therapistId(), r));
        return out;
    }

    /**
     * 整表替换而不是逐行 upsert：技师在窗口内掉到零单零评时，upsert 会把上一轮的旧数字留在表里，
     * 客户看到的是一个月前的成绩。
     */
    @Override
    @Transactional
    public void replaceAll(List<TherapistStat> stats) {
        jdbc.update("DELETE FROM therapist_stat_30d");
        for (TherapistStat s : stats) {
            jdbc.update(
                    "INSERT INTO therapist_stat_30d (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    s.therapistId(), s.servedCount(), s.repeatCount(), s.repeatCustomerCount(),
                    s.reviewCount(), s.positiveCount(), s.positiveRateX100(), s.avgScoreX100(),
                    Date.valueOf(s.windowStart()), Date.valueOf(s.windowEnd()),
                    JdbcTimes.ts(s.calcAt()));
        }
    }

    /**
     * 先 {@code FOR UPDATE} 锁行再在 Java 里算，不在 UPDATE 语句里做自增派生计算 ——
     * MySQL 的 UPDATE 赋值是从左往右求值的，后面的表达式看到的是前面**已更新**的列值，
     * 把 {@code positive_count} 和由它派生的 {@code positive_rate_x100} 写在同一条语句里会重复计数。
     */
    @Override
    @Transactional
    public void bumpReview(long therapistId, int score, boolean positive, Instant now) {
        LocalDate today = clock.today();
        TherapistStat cur = jdbc.query(
                        "SELECT " + COLUMNS + " FROM therapist_stat_30d WHERE therapist_id = ? FOR UPDATE",
                        ROW,
                        therapistId)
                .stream()
                .findFirst()
                .orElse(null);

        if (cur == null) {
            jdbc.update(
                    "INSERT INTO therapist_stat_30d (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    therapistId, 0, 0, 0, 1, positive ? 1 : 0,
                    TherapistStatCalculator.positiveRateX100(positive ? 1 : 0, 1),
                    TherapistStatCalculator.avgScoreX100(score, 1),
                    Date.valueOf(today.minusDays(ReviewPolicy.STAT_WINDOW_DAYS)),
                    Date.valueOf(today), JdbcTimes.ts(now));
            return;
        }

        int reviewCount = cur.reviewCount() + 1;
        int positiveCount = cur.positiveCount() + (positive ? 1 : 0);
        // 均分从 avg*count 反推，有小数点后的漂移；日更 job 全量重算会校正回来。
        int scoreSum = (int) Math.round(cur.avgScoreX100() * (long) cur.reviewCount() / 100.0) + score;
        jdbc.update(
                """
                UPDATE therapist_stat_30d
                   SET review_count = ?, positive_count = ?,
                       positive_rate_x100 = ?, avg_score_x100 = ?,
                       window_end = ?, calc_at = ?
                 WHERE therapist_id = ?
                """,
                reviewCount,
                positiveCount,
                TherapistStatCalculator.positiveRateX100(positiveCount, reviewCount),
                TherapistStatCalculator.avgScoreX100(scoreSum, reviewCount),
                Date.valueOf(today),
                JdbcTimes.ts(now),
                therapistId);
    }
}
