package com.jisuodashi.growth;

import java.time.Instant;
import java.time.LocalDate;

public final class GrowthModels {

    private GrowthModels() {
    }

    public record FollowUp(
            long id, long customerId, long storeId, long staffId,
            String roleKind, String kind, String channel,
            String content, String outcome,
            LocalDate dueOn, Instant doneAt, Instant createdAt
    ) {
        public boolean pending() {
            return doneAt == null;
        }

        /** 过期未做：完成度按这个扣分，而不是按"今天有没有做"。 */
        public boolean overdueOn(LocalDate day) {
            return pending() && dueOn != null && day.isAfter(dueOn);
        }
    }

    public record Attendance(
            long id, long staffId, long storeId, LocalDate workDay,
            Instant clockInAt, Instant clockOutAt, Instant createdAt, Instant updatedAt
    ) {
        /** 出勤只认打了上班卡的天：只打下班卡的多半是补卡，不算数。 */
        public boolean present() {
            return clockInAt != null;
        }
    }

    public record Checkin(
            long id, long customerId, long storeId, Long planId,
            LocalDate checkDay, String content, String imageUrls,
            String visibility, int likeCount, Instant createdAt
    ) {
        public boolean isPublic() {
            return VISIBILITY_PUBLIC.equals(visibility);
        }
    }

    public record Assessment(
            long id, long customerId, long storeId, Long therapistId, Long planId,
            String phase, LocalDate assessedOn, String summary, String itemsJson,
            Instant createdAt
    ) {
    }

    public record StageReview(
            long id, long customerId, long storeId, Long therapistId, Long planId,
            String direction, int score, String commentText, Instant createdAt
    ) {
    }

    public record ExternalClaim(
            long id, long storeId, long therapistId, Long customerId,
            String platform, int rating, String proofUrl, LocalDate claimedOn,
            String status, Long reviewedBy, Instant createdAt, Instant updatedAt
    ) {
        public boolean approved() {
            return CLAIM_APPROVED.equals(status);
        }
    }

    // 回访
    public static final String ROLE_THERAPIST = "THERAPIST";
    public static final String ROLE_MANAGER = "MANAGER";
    public static final String FU_AFTER_CLASS = "AFTER_CLASS";
    public static final String FU_RETENTION = "RETENTION";
    public static final String FU_REVIVE = "REVIVE";
    public static final String FU_TRIAL = "TRIAL";

    // 打卡可见性
    public static final String VISIBILITY_PUBLIC = "PUBLIC";
    public static final String VISIBILITY_PRIVATE = "PRIVATE";

    // 评估阶段
    public static final String PHASE_BASELINE = "BASELINE";
    public static final String PHASE_MID = "MID";
    public static final String PHASE_FINAL = "FINAL";

    // 打分方向
    public static final String DIR_C2T = "C2T";
    public static final String DIR_SYS2C = "SYS2C";

    // 好评认领
    public static final String CLAIM_PENDING = "PENDING";
    public static final String CLAIM_APPROVED = "APPROVED";
    public static final String CLAIM_REJECTED = "REJECTED";

    /** 获客渠道。转介绍单独成项，因为它要记介绍人。 */
    public static final java.util.List<String> CHANNELS = java.util.List.of(
            "XIAOHONGSHU", "DOUYIN", "DIANPING", "MEITUAN", "REFERRAL", "WALK_IN", "FRIEND", "OTHER");

    public static String channelLabel(String code) {
        return switch (code == null ? "" : code) {
            case "XIAOHONGSHU" -> "小红书";
            case "DOUYIN" -> "抖音";
            case "DIANPING" -> "大众点评";
            case "MEITUAN" -> "美团";
            case "REFERRAL" -> "转介绍";
            case "WALK_IN" -> "自然到店";
            case "FRIEND" -> "朋友推荐";
            case "OTHER" -> "其他";
            default -> "未填";
        };
    }
}
