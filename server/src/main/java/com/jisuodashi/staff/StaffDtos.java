package com.jisuodashi.staff;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public final class StaffDtos {

    private StaffDtos() {
    }

    public record TodayBoard(NextJob next, List<TimelineSlot> timeline) {
    }

    public record NextJob(
            String orderId,
            String status,
            String start,
            String end,
            String projectName,
            String roomName,
            String bedName,
            String customerName,
            boolean isNewCustomer,
            long minutesToStart
    ) {
    }

    public record TimelineSlot(int slotNo, String state, String orderId) {
    }

    public record OrderActionRequest(@NotBlank(message = "requestId 不能为空") String requestId) {
    }

    public record OrderActionResponse(String orderId, String status, String requestId) {
    }

    public record AppendNoteRequest(
            @NotBlank(message = "content 不能为空") String content,
            Boolean consent
    ) {
        public AppendNoteRequest(String content) {
            this(content, null);
        }
    }

    public record AppendNoteResponse(String id, String orderId, String content, String createdAt) {
    }

    public record ConsentResponse(String orderId, boolean consented, String consentedAt) {
    }
}
