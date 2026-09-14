package com.jisuodashi.growth;

import java.util.List;

public final class GrowthDtos {

    private GrowthDtos() {
    }

    // ── 回访 ──
    public record FollowUpItem(
            String followUpId, String customerId, String customerMask, String wxNickname,
            String kind, String kindLabel, String channel, String content, String outcome,
            String dueOn, String doneAt, boolean pending, boolean overdue
    ) {
    }

    public record FollowUpListResponse(
            List<FollowUpItem> items, int pending, int overdue, int doneRateX100) {
    }

    public record FollowUpRequest(
            String requestId, String customerId, String kind, String channel,
            String content, String outcome, String dueOn) {
    }

    // ── 考勤 ──
    public record AttendanceResponse(
            String workDay, String clockInAt, String clockOutAt,
            int monthPresentDays, boolean onDuty) {
    }

    // ── 课后打卡 ──
    public record CheckinItem(
            String checkinId, String customerId, String customerMask,
            String checkDay, String content, List<String> images,
            int likeCount, boolean mine) {
    }

    public record CheckinFeedResponse(List<CheckinItem> items) {
    }

    public record CheckinRequest(
            String requestId, String checkDay, String content,
            String imageUrls, String visibility, String planId) {
    }

    // ── 评估 ──
    public record AssessItem(String name, String value, String unit, String note) {
    }

    public record AssessmentItem(
            String assessmentId, String phase, String phaseLabel, String assessedOn,
            String summary, List<AssessItem> items) {
    }

    /** 结果比对：把 BASELINE 和最新一次并排，差值直接算好。 */
    public record AssessCompareRow(
            String name, String unit, String baseline, String latest, String delta) {
    }

    public record AssessmentResponse(
            List<AssessmentItem> history, List<AssessCompareRow> compare) {
    }

    public record AssessmentRequest(
            String requestId, String phase, String assessedOn, String summary,
            String planId, List<AssessItem> items) {
    }

    // ── 双向打分 ──
    public record StageReviewRequest(
            String requestId, String therapistId, String planId, int score, String comment) {
    }

    public record StageReviewItem(
            String reviewId, String direction, int score, String comment,
            String customerMask, String createdAt) {
    }

    // ── 外部好评认领 ──
    public record ClaimRequest(
            String requestId, String platform, int rating, String proofUrl,
            String customerId, String claimedOn) {
    }

    public record ClaimItem(
            String claimId, String platform, String platformLabel, int rating,
            String therapistId, String therapistName, String proofUrl,
            String claimedOn, String status, String statusLabel) {
    }

    public record ClaimListResponse(List<ClaimItem> items, int pending, int approved) {
    }

    // ── 老师成果 ──
    public record RankRow(String metric, String metricLabel, String value, int rank, int total) {
    }

    public record ScoreboardResponse(
            String therapistId, String therapistName, String month,
            int lessonCount, String salesYuan, int positiveRateX100,
            int renewRateX100, int convertRateX100,
            int followUpRateX100, int profileRateX100, int complianceX100,
            int attendanceDays,
            List<RankRow> ranks, String encouragement) {
    }
}
