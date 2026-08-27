package com.jisuodashi.employment;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public final class EmploymentDtos {

    private EmploymentDtos() {
    }

    public record OnboardRequest(
            String requestId,
            @NotBlank(message = "username 不能为空") String username,
            @NotBlank(message = "name 不能为空") String name,
            String phone,
            List<String> roleCodes,
            String scopeType,
            List<String> storeIds,
            String effectiveOn
    ) {
    }

    public record OffboardRequest(
            String requestId,
            String effectiveOn,
            String reason,
            /** 有未来已支付订单时默认阻断；确认已改约或退款后可强制放行。 */
            Boolean force
    ) {
    }

    public record StaffItem(
            String staffId,
            String username,
            String name,
            int status,
            String statusLabel,
            List<String> roleCodes,
            String scopeType,
            String therapistId,
            String level,
            int pendingOrders
    ) {
    }

    public record StaffListResponse(List<StaffItem> items, int total) {
    }

    public record LogItem(
            String logId,
            String staffId,
            String staffName,
            String action,
            String effectiveOn,
            String reason,
            String createdAt
    ) {
    }

    public record LogListResponse(List<LogItem> items) {
    }

    /** 离职被挡时把冲突单摆出来，让 admin 知道要先处理哪些。 */
    public record BlockedOrder(String orderId, String orderNo, String date, String start, String status) {
    }

    public record ActionResponse(
            String staffId,
            /** 入职时若含 THERAPIST 角色则一并建了技师档案，其余动作为 null。 */
            String therapistId,
            String action,
            int status,
            String effectiveOn,
            List<BlockedOrder> blockedOrders
    ) {
    }
}
