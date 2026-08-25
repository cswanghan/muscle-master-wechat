package com.jisuodashi.review;

import java.time.Instant;
import java.time.LocalDate;

/** {@code therapist_stat_30d} 一行。所有比率用 x100 整数，避免浮点在展示层抖动。 */
public record TherapistStat(
        long therapistId,
        int servedCount,
        int repeatCount,
        int repeatCustomerCount,
        int reviewCount,
        int positiveCount,
        int positiveRateX100,
        int avgScoreX100,
        LocalDate windowStart,
        LocalDate windowEnd,
        Instant calcAt
) {
    public static TherapistStat empty(long therapistId, LocalDate start, LocalDate end, Instant calcAt) {
        return new TherapistStat(therapistId, 0, 0, 0, 0, 0, 0, 0, start, end, calcAt);
    }
}
