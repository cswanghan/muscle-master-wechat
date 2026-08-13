package com.jisuodashi.common;

import org.springframework.stereotype.Component;

/**
 * Feature flags from {@code app.flags} / env, refreshed at most every 5s.
 */
@Component
public class FeatureFlags {

    public static final long REFRESH_MS = 5_000L;

    private final AppProperties properties;
    private final AppClock clock;
    private volatile Snapshot snap;
    private volatile long loadedAt;

    public FeatureFlags(AppProperties properties, AppClock clock) {
        this.properties = properties;
        this.clock = clock;
        reload();
    }

    public void refresh() {
        reload();
    }

    public boolean bookingEnabled() {
        return current().bookingEnabled;
    }

    public boolean bookingLockEnabled() {
        return current().bookingLockEnabled;
    }

    public boolean payWechatEnabled() {
        return current().payWechatEnabled;
    }

    public boolean workflowRescheduleEnabled() {
        return current().workflowRescheduleEnabled;
    }

    public boolean workflowAddOnEnabled() {
        return current().workflowAddOnEnabled;
    }

    public boolean workflowSwapEnabled() {
        return current().workflowSwapEnabled;
    }

    public boolean workflowRefundEnabled() {
        return current().workflowRefundEnabled;
    }

    public void assertBookingWritable() {
        if (!bookingEnabled()) {
            throw new ApiException(ErrorCodes.FORBIDDEN, "预约已关闭");
        }
        if (!bookingLockEnabled()) {
            throw new ApiException(ErrorCodes.FORBIDDEN, "锁库存已关闭");
        }
    }

    public void assertWechatPay() {
        if (!payWechatEnabled()) {
            throw new ApiException(ErrorCodes.FORBIDDEN, "微信支付已关闭");
        }
    }

    public void assertWorkflow(boolean enabled, String name) {
        if (!enabled) {
            throw new ApiException(ErrorCodes.FORBIDDEN, name + " 已关闭");
        }
    }

    private Snapshot current() {
        long now = clock.instant().toEpochMilli();
        if (now - loadedAt >= REFRESH_MS) {
            synchronized (this) {
                if (now - loadedAt >= REFRESH_MS) {
                    reload();
                }
            }
        }
        return snap;
    }

    private void reload() {
        AppProperties.Flags flags = properties.getFlags();
        snap = new Snapshot(
                envOr("BOOKING_ENABLED", "APP_FLAGS_BOOKING_ENABLED", flags.getBooking().isEnabled()),
                envOr("BOOKING_LOCK_ENABLED", "APP_FLAGS_BOOKING_LOCK_ENABLED",
                        flags.getBooking().getLock().isEnabled()),
                envOr("PAY_WECHAT_ENABLED", "APP_FLAGS_PAY_WECHAT_ENABLED",
                        flags.getPay().getWechat().isEnabled()),
                envOr("WORKFLOW_RESCHEDULE_ENABLED", "APP_FLAGS_WORKFLOW_RESCHEDULE_ENABLED",
                        flags.getWorkflow().getReschedule().isEnabled()),
                envOr("WORKFLOW_ADD_ON_ENABLED", "APP_FLAGS_WORKFLOW_ADD_ON_ENABLED",
                        flags.getWorkflow().getAddOn().isEnabled()),
                envOr("WORKFLOW_SWAP_ENABLED", "APP_FLAGS_WORKFLOW_SWAP_ENABLED",
                        flags.getWorkflow().getSwap().isEnabled()),
                envOr("WORKFLOW_REFUND_ENABLED", "APP_FLAGS_WORKFLOW_REFUND_ENABLED",
                        flags.getWorkflow().getRefund().isEnabled()));
        loadedAt = clock.instant().toEpochMilli();
    }

    private static boolean envOr(String env, String alt, boolean fallback) {
        Boolean first = parseBool(System.getenv(env));
        if (first != null) {
            return first;
        }
        Boolean second = parseBool(System.getenv(alt));
        if (second != null) {
            return second;
        }
        Boolean prop = parseBool(System.getProperty(env));
        return prop != null ? prop : fallback;
    }

    private static Boolean parseBool(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        if ("1".equals(v) || "true".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v)) {
            return true;
        }
        if ("0".equals(v) || "false".equalsIgnoreCase(v) || "no".equalsIgnoreCase(v) || "off".equalsIgnoreCase(v)) {
            return false;
        }
        return null;
    }

    private record Snapshot(
            boolean bookingEnabled,
            boolean bookingLockEnabled,
            boolean payWechatEnabled,
            boolean workflowRescheduleEnabled,
            boolean workflowAddOnEnabled,
            boolean workflowSwapEnabled,
            boolean workflowRefundEnabled
    ) {
    }
}
