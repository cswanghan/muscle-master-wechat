package com.jisuodashi.review;

import com.jisuodashi.review.ServedVisitSource.ServedVisit;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 口径测试。这两条最容易在后续重构里被悄悄改坏：
 * 首访序号必须在全历史上排，好评率必须向下取整。
 */
class TherapistStatCalculatorTest {

    private static final long T1 = 401L;
    private static final long T2 = 402L;
    private static final long C1 = 901L;
    private static final long C2 = 902L;

    private static final LocalDate WINDOW_END = LocalDate.of(2026, 8, 25);
    private static final LocalDate WINDOW_START = WINDOW_END.minusDays(30);
    private static final Instant WINDOW_START_AT = Instant.parse("2026-07-26T00:00:00Z");
    private static final Instant CALC_AT = Instant.parse("2026-08-25T02:40:00Z");

    @Test
    void firstVisitRankedOverFullHistoryNotOverWindow() {
        // C1 的首访在窗口外（半年前），窗口内两次都算回头。
        // 若先截窗口再排序，窗口内第一次会被当成首访，回头数系统性偏低。
        List<ServedVisit> visits = List.of(
                new ServedVisit(T1, C1, Instant.parse("2026-02-01T03:00:00Z")),
                new ServedVisit(T1, C1, Instant.parse("2026-08-01T03:00:00Z")),
                new ServedVisit(T1, C1, Instant.parse("2026-08-10T03:00:00Z")));

        TherapistStat stat = only(compute(visits, List.of()));

        assertThat(stat.servedCount()).as("窗口外那次不计入服务次数").isEqualTo(2);
        assertThat(stat.repeatCount()).as("窗口内两次都是回头").isEqualTo(2);
        assertThat(stat.repeatCustomerCount()).as("回头人数去重").isEqualTo(1);
    }

    @Test
    void repeatIsPerTherapistCustomerPairNotPerCustomer() {
        // 同一个客人在 T1 是老客、在 T2 是首访：回头按「技师+客人」这一对算。
        List<ServedVisit> visits = List.of(
                new ServedVisit(T1, C1, Instant.parse("2026-08-01T03:00:00Z")),
                new ServedVisit(T1, C1, Instant.parse("2026-08-11T03:00:00Z")),
                new ServedVisit(T2, C1, Instant.parse("2026-08-12T03:00:00Z")));

        List<TherapistStat> stats = compute(visits, List.of());

        assertThat(byId(stats, T1).repeatCount()).isEqualTo(1);
        assertThat(byId(stats, T2).repeatCount()).as("换了技师就是首访").isZero();
        assertThat(byId(stats, T2).servedCount()).isEqualTo(1);
    }

    @Test
    void repeatCustomerCountDedupesWhileRepeatCountDoesNot() {
        // C1 来 3 次（2 次回头），C2 来 2 次（1 次回头）：次数 3、人数 2。
        List<ServedVisit> visits = List.of(
                new ServedVisit(T1, C1, Instant.parse("2026-08-01T03:00:00Z")),
                new ServedVisit(T1, C1, Instant.parse("2026-08-05T03:00:00Z")),
                new ServedVisit(T1, C1, Instant.parse("2026-08-09T03:00:00Z")),
                new ServedVisit(T1, C2, Instant.parse("2026-08-02T03:00:00Z")),
                new ServedVisit(T1, C2, Instant.parse("2026-08-08T03:00:00Z")));

        TherapistStat stat = only(compute(visits, List.of()));

        assertThat(stat.servedCount()).isEqualTo(5);
        assertThat(stat.repeatCount()).isEqualTo(3);
        assertThat(stat.repeatCustomerCount()).isEqualTo(2);
    }

    @Test
    void positiveRateFloorsSoNinetyNinePointSixNeverReadsAsHundred() {
        assertThat(TherapistStatCalculator.positiveRateX100(249, 250)).isEqualTo(9960);
        assertThat(TherapistStatCalculator.positiveRateX100(2, 3)).as("66.66% 截断不进位").isEqualTo(6666);
        assertThat(TherapistStatCalculator.positiveRateX100(1, 1)).as("全好评才是 100%").isEqualTo(10000);
        assertThat(TherapistStatCalculator.positiveRateX100(0, 0)).as("零评价不除零").isZero();
    }

    @Test
    void avgScoreRoundsHalfUp() {
        assertThat(TherapistStatCalculator.avgScoreX100(9, 2)).as("4.5 星").isEqualTo(450);
        assertThat(TherapistStatCalculator.avgScoreX100(14, 3)).as("4.666… 进到 4.67").isEqualTo(467);
        assertThat(TherapistStatCalculator.avgScoreX100(0, 0)).isZero();
    }

    @Test
    void positiveIsReadFromTheRowNotRecomputedFromScore() {
        // 存量行的 positive 落库时已定格。即便阈值以后改了，重算也要原样读。
        List<OrderReview> reviews = List.of(
                review(1L, T1, 5, true),
                review(2L, T1, 3, true),
                review(3L, T1, 5, false));

        TherapistStat stat = only(compute(List.of(), reviews));

        assertThat(stat.reviewCount()).isEqualTo(3);
        assertThat(stat.positiveCount()).as("按行上的 positive，不按 score>=4 现算").isEqualTo(2);
        assertThat(stat.positiveRateX100()).isEqualTo(6666);
    }

    @Test
    void takenDownReviewsLeaveTheStats() {
        List<OrderReview> reviews = List.of(
                review(1L, T1, 5, true),
                takenDown(review(2L, T1, 1, false)));

        TherapistStat stat = only(compute(List.of(), reviews));

        assertThat(stat.reviewCount()).isEqualTo(1);
        assertThat(stat.positiveRateX100()).isEqualTo(10000);
    }

    @Test
    void therapistWithReviewsButNoVisitsStillGetsARow() {
        // 服务发生在窗口外、评价落在窗口内：两边取并集才不会漏人。
        TherapistStat stat = only(compute(List.of(), List.of(review(1L, T1, 4, true))));

        assertThat(stat.therapistId()).isEqualTo(T1);
        assertThat(stat.servedCount()).isZero();
        assertThat(stat.reviewCount()).isEqualTo(1);
    }

    private static List<TherapistStat> compute(List<ServedVisit> visits, List<OrderReview> reviews) {
        return TherapistStatCalculator.compute(
                visits, reviews, WINDOW_START, WINDOW_END, WINDOW_START_AT, CALC_AT);
    }

    private static TherapistStat only(List<TherapistStat> stats) {
        assertThat(stats).hasSize(1);
        return stats.get(0);
    }

    private static TherapistStat byId(List<TherapistStat> stats, long therapistId) {
        return stats.stream().filter(s -> s.therapistId() == therapistId).findFirst().orElseThrow();
    }

    private static OrderReview review(long id, long therapistId, int score, boolean positive) {
        return new OrderReview(id, id, C1, therapistId, 3100L, score, positive,
                null, null, false, 1, Instant.parse("2026-08-10T03:00:00Z"));
    }

    private static OrderReview takenDown(OrderReview r) {
        return new OrderReview(r.id(), r.orderId(), r.customerId(), r.therapistId(), r.storeId(),
                r.score(), r.positive(), r.tags(), r.content(), r.anonymous(), 0, r.createdAt());
    }
}
