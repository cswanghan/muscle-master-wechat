package com.jisuodashi.level;

import java.time.Instant;

public final class LevelModels {

    private LevelModels() {
    }

    /** {@code therapist_level_config} 一行。{@code priceDeltaFen} P1 不读，P2 接定价时才用。 */
    public record LevelConfig(
            String level,
            int sortNo,
            String displayName,
            int priceDeltaFen,
            int minReviewCount,
            int minPositiveRateX100,
            int status
    ) {
    }

    /** {@code therapist_level_log} 一行。 */
    public record LevelLog(
            long id,
            long therapistId,
            String fromLevel,
            String toLevel,
            String reason,
            Long operatorId,
            String snapshotJson,
            int status,
            Instant createdAt,
            Instant decidedAt
    ) {
    }

    /** 累计口径的晋升判定输入，与 30 天展示统计分开。 */
    public record LifetimeStat(long therapistId, int reviewCount, int positiveCount) {
        public int positiveRateX100() {
            return reviewCount == 0 ? 0 : Math.round((float) positiveCount * 10_000 / reviewCount);
        }
    }

    public static final String REASON_AUTO_ELIGIBLE = "AUTO_ELIGIBLE";
    public static final String REASON_ADMIN_CONFIRM = "ADMIN_CONFIRM";
    public static final String REASON_ADMIN_REJECT = "ADMIN_REJECT";

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_APPLIED = 1;
    public static final int STATUS_REJECTED = 2;
}
