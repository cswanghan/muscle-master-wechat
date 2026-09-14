package com.jisuodashi.membership;

import java.time.Instant;
import java.time.LocalDate;

public final class MembershipModels {

    private MembershipModels() {
    }

    /** 会员档案。一人一行，可编辑；与只增不改的 {@code treatment_note} 是两回事。 */
    public record Profile(
            long id,
            long customerId,
            long storeId,
            Long ownerTherapistId,
            String coreIssue,
            String remark,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record Package(
            long id,
            long customerId,
            long storeId,
            long projectId,
            String title,
            int totalSessions,
            int usedSessions,
            long priceFen,
            long unitPriceFen,
            Long sellerTherapistId,
            LocalDate effectiveOn,
            LocalDate expireOn,
            int status,
            Instant createdAt,
            Instant updatedAt
    ) {
        public int remainingSessions() {
            return Math.max(0, totalSessions - usedSessions);
        }

        /** 过期只看日期，不看 status —— 夜间任务没跑到之前，状态位还没翻。 */
        public boolean expiredOn(LocalDate day) {
            return expireOn != null && day.isAfter(expireOn);
        }

        public boolean usableOn(LocalDate day) {
            return status == STATUS_ACTIVE && remainingSessions() > 0 && !expiredOn(day);
        }
    }

    public record Txn(
            long id,
            long memberPackageId,
            long customerId,
            long storeId,
            String type,
            int deltaSessions,
            Long orderId,
            Long therapistId,
            String requestId,
            String remark,
            Instant createdAt
    ) {
    }

    public record Plan(
            long id,
            long customerId,
            long storeId,
            Long therapistId,
            String title,
            String goal,
            int totalSessions,
            int doneSessions,
            int weeklyFrequency,
            LocalDate startOn,
            LocalDate endOn,
            int status,
            Instant createdAt,
            Instant updatedAt
    ) {
        public int progressX100() {
            return totalSessions <= 0 ? 0 : Math.min(10_000, doneSessions * 10_000 / totalSessions);
        }
    }

    public static final String TYPE_PURCHASE = "PURCHASE";
    public static final String TYPE_CONSUME = "CONSUME";
    public static final String TYPE_REFUND = "REFUND";
    public static final String TYPE_ADJUST = "ADJUST";

    public static final int STATUS_DISABLED = 0;
    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_USED_UP = 2;
    public static final int STATUS_EXPIRED = 3;

    public static final int PLAN_STOPPED = 0;
    public static final int PLAN_RUNNING = 1;
    public static final int PLAN_DONE = 2;
}
