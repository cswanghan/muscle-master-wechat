package com.jisuodashi.membership;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public final class MembershipDtos {

    private MembershipDtos() {
    }

    /** 老师端会员列表的一行。一眼要能看出：还剩几次、练到哪了、主要问题是什么。 */
    public record MemberItem(
            String customerId,
            String phoneMask,
            String coreIssue,
            int remainingSessions,
            int doneSessions,
            String planTitle,
            int planDone,
            int planTotal,
            int planProgressX100,
            String nextLessonAt
    ) {
    }

    public record MemberListResponse(List<MemberItem> items, int total) {
    }

    public record PackageItem(
            String packageId,
            String title,
            int totalSessions,
            int usedSessions,
            int remainingSessions,
            String unitPriceYuan,
            String expireOn,
            boolean usable,
            String statusLabel
    ) {
    }

    public record PlanItem(
            String planId,
            String title,
            String goal,
            int totalSessions,
            int doneSessions,
            int progressX100,
            int weeklyFrequency,
            String startOn,
            String endOn,
            String statusLabel
    ) {
    }

    public record MemberDetail(
            String customerId,
            String phoneMask,
            String coreIssue,
            String remark,
            String ownerTherapistId,
            int remainingSessions,
            int doneSessions,
            List<PackageItem> packages,
            List<PlanItem> plans
    ) {
    }

    /** 当日可约。一行一个时段，占用的也给出来，老师要看到整天的样子而不只是空的。 */
    public record SlotItem(
            int slotNo,
            String start,
            String state,
            boolean bookable,
            String customerName,
            String orderId
    ) {
    }

    public record DaySlotsResponse(
            String date,
            String therapistId,
            String therapistName,
            int freeCount,
            int bookedCount,
            List<SlotItem> slots
    ) {
    }

    public record SellPackageRequest(
            String requestId,
            @NotBlank(message = "phone 不能为空") String phone,
            String projectId,
            int totalSessions,
            long priceFen,
            String sellerTherapistId,
            Integer validDays
    ) {
    }

    public record SellPackageResponse(
            String packageId,
            String customerId,
            String title,
            int totalSessions,
            String unitPriceYuan,
            String expireOn,
            long saleCommissionFen
    ) {
    }

    public record ProfileRequest(
            String requestId,
            String coreIssue,
            String remark,
            String ownerTherapistId
    ) {
    }
}
