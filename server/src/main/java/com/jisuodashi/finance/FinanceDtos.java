package com.jisuodashi.finance;

import java.util.List;

public final class FinanceDtos {

    private FinanceDtos() {
    }

    // ── 支出 ──
    public record ExpenseRequest(
            String requestId, String category, long amountFen, String happenedOn,
            String vendor, String remark, String proofUrl) {
    }

    public record ExpenseItem(
            String expenseId, String category, String categoryLabel, String amountYuan,
            String happenedOn, String vendor, String remark, String proofUrl,
            String status, String statusLabel, String submitterName, String rejectReason) {
    }

    public record ExpenseListResponse(
            List<ExpenseItem> items, int pending, String approvedTotalYuan) {
    }

    // ── 工资口径 ──
    public record PayrollConfigRequest(
            String requestId, String therapistId,
            long baseSalaryFen, long lessonFeeFen, int saleRateX100) {
    }

    // ── 报表 ──

    /** 41 当月耗课收入 */
    public record ConsumeRow(
            String customerMask, int lessonCount, String unitPriceYuan,
            String amountYuan, String therapistName) {
    }

    public record ConsumeReport(String month, List<ConsumeRow> rows, String totalYuan, int totalLessons) {
    }

    /** 42 当月体验课 */
    public record TrialRow(
            String date, String customerMask, String therapistName,
            String channel, String channelLabel, String amountYuan, boolean converted) {
    }

    public record TrialReport(
            String month, List<TrialRow> rows, int totalCount, String totalYuan,
            int convertedCount, int convertRateX100, List<ChannelRow> byChannel) {
    }

    public record ChannelRow(
            String channel, String channelLabel, int count, int converted, int convertRateX100) {
    }

    /** 43 当月退费 */
    public record RefundRow(
            String date, String customerMask, String amountYuan,
            String reasonCode, String reasonLabel, String liableTherapistName) {
    }

    public record RefundReport(String month, List<RefundRow> rows, String totalYuan, int totalCount) {
    }

    /** 44 当月老师工资 */
    public record PayrollRow(
            String therapistId, String therapistName, int attendanceDays, int lessonCount,
            String lessonFeeYuan, String salesYuan, int saleRateX100, String saleCommissionYuan,
            String baseSalaryYuan, String totalYuan) {
    }

    public record PayrollReport(String month, List<PayrollRow> rows, String totalYuan) {
    }

    /** 45 库存课（未耗课时 = 负债） */
    public record InventoryRow(
            String customerMask, String title, int remainingSessions,
            String unitPriceYuan, String valueYuan, String expireOn, boolean expiringSoon) {
    }

    public record InventoryReport(
            String asOf, List<InventoryRow> rows, int totalSessions,
            String totalValueYuan, String expiringValueYuan) {
    }

    /** 46 门店整体业绩 */
    public record AmountRow(String key, String label, String amountYuan) {
    }

    public record StoreReport(
            String month,
            List<AmountRow> income, String incomeTotalYuan,
            List<AmountRow> expense, String expenseTotalYuan,
            String profitYuan, boolean profitable) {
    }

    // ── 客户报表（39） ──
    public record CustomerRow(
            String customerId, String customerMask, String wxNickname,
            int remainingSessions, String expireOn, String lastVisitOn,
            int dormantDays, String ownerTherapistName) {
    }

    public record CustomerReport(
            String kind, String kindLabel, List<CustomerRow> rows, int total) {
    }
}
