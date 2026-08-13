package com.jisuodashi.catalog;

import java.math.BigDecimal;
import java.util.List;

public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record StoreListItem(
            String storeId,
            String name,
            Integer distanceM,
            boolean near,
            String businessStart,
            String businessEnd,
            boolean open
    ) {
    }

    public record ProjectSummary(
            String projectId,
            String name,
            int durationMinutes,
            int bufferMinutes,
            long priceFen,
            String description,
            String coverUrl
    ) {
    }

    public record StoreDetail(
            String storeId,
            String code,
            String name,
            String address,
            BigDecimal lng,
            BigDecimal lat,
            String businessStart,
            String businessEnd,
            boolean open,
            List<ProjectSummary> projects
    ) {
    }

    public record TherapistItem(
            String therapistId,
            String name,
            String employeeNo,
            String level,
            int ratingX100,
            String intro,
            String avatarUrl,
            String homeStoreId
    ) {
    }

    public record SymptomItem(
            String id,
            String parentId,
            String type,
            String name
    ) {
    }

    public record Page<T>(List<T> items, String nextCursor) {
        public static <T> Page<T> of(List<T> items) {
            return new Page<>(items, null);
        }
    }

    public record SymptomProjects(List<ProjectSummary> items, String hint) {
    }
}
