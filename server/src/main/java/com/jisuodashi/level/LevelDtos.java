package com.jisuodashi.level;

import java.util.List;

public final class LevelDtos {

    private LevelDtos() {
    }

    public record PendingItem(
            String logId,
            String therapistId,
            String therapistName,
            String fromLevel,
            String toLevel,
            String toLevelName,
            int reviewCount,
            int positiveRateX100,
            String createdAt
    ) {
    }

    public record PendingListResponse(List<PendingItem> items, int total) {
    }

    public record DecideRequest(String requestId, String note) {
    }

    public record DecideResponse(
            String logId,
            String therapistId,
            String fromLevel,
            String toLevel,
            String result,
            String decidedAt
    ) {
    }

    public record ScanResponse(int scanned, int proposed) {
    }
}
