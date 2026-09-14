package com.jisuodashi.notify;

import java.time.Instant;
import java.time.LocalDate;

public final class NotifyModels {

    private NotifyModels() {
    }

    /**
     * 一次订阅额度。
     *
     * <p>微信订阅消息的硬规则：**用户点一次「允许」，才能推一条**。所以做不到
     * "买了 12 次课就自动提醒 12 次"。每次约课时前端要一次授权，换来这里的一条额度，
     * 推完即销。消息里带「约下一次」的跳转，用户点进来再授权一次 —— 行业通行做法，
     * 但依赖用户点。要无条件必达只能上短信。
     */
    public record Grant(
            long id, long customerId, String templateId, Long orderId,
            String status, Instant grantedAt, Instant sentAt, String failReason
    ) {
        public boolean usable() {
            return STATUS_GRANTED.equals(status);
        }
    }

    /** 待推的提醒。job 扫这张表，到点了才去找额度。 */
    public record Reminder(
            long id, long customerId, long orderId, long storeId,
            String kind, LocalDate serviceDate, Instant fireAt,
            String status, Instant sentAt, String failReason, Instant createdAt
    ) {
        public boolean pending() {
            return STATUS_PENDING.equals(status);
        }
    }

    public static final String STATUS_GRANTED = "GRANTED";
    public static final String STATUS_USED = "USED";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_SKIPPED = "SKIPPED";
    public static final String STATUS_FAILED = "FAILED";

    public static final String KIND_BEFORE_CLASS = "BEFORE_CLASS";

    /** 提前多少小时提醒。太早会被忘掉，太晚来不及出门。 */
    public static final int LEAD_HOURS = 3;
}
