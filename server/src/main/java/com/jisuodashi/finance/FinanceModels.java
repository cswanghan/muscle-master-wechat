package com.jisuodashi.finance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class FinanceModels {

    private FinanceModels() {
    }

    public record Expense(
            long id, long storeId, String category, long amountFen, LocalDate happenedOn,
            String vendor, String remark, String proofUrl, String status,
            long submittedBy, Long reviewedBy, Instant reviewedAt, String rejectReason,
            Instant createdAt, Instant updatedAt
    ) {
        public boolean approved() {
            return STATUS_APPROVED.equals(status);
        }
    }

    public record Payroll(
            long id, long therapistId, long storeId,
            long baseSalaryFen, long lessonFeeFen, int saleRateX100,
            LocalDate effectiveOn, Instant createdAt, Instant updatedAt
    ) {
    }

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    public static final List<String> CATEGORIES = List.of(
            "MATERIAL", "RENT", "PROPERTY", "UTILITY", "LABOR", "PROMOTION", "OTHER");

    public static String categoryLabel(String c) {
        return switch (c == null ? "" : c) {
            case "MATERIAL" -> "物料";
            case "RENT" -> "场地租金";
            case "PROPERTY" -> "物业费";
            case "UTILITY" -> "水电费";
            case "LABOR" -> "人工成本";
            case "PROMOTION" -> "推广支出";
            case "OTHER" -> "其他";
            default -> c;
        };
    }

    public static final List<String> REFUND_REASONS = List.of(
            "EFFECT", "SERVICE", "SCHEDULE", "RELOCATE", "HEALTH", "OTHER");

    public static String refundReasonLabel(String c) {
        return switch (c == null ? "" : c) {
            case "EFFECT" -> "效果不满意";
            case "SERVICE" -> "服务问题";
            case "SCHEDULE" -> "时间冲突";
            case "RELOCATE" -> "搬家";
            case "HEALTH" -> "身体原因";
            case "OTHER" -> "其他";
            default -> "未填";
        };
    }
}
