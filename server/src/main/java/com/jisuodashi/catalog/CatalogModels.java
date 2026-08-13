package com.jisuodashi.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public final class CatalogModels {

    private CatalogModels() {
    }

    public record Store(
            long id,
            String code,
            String name,
            byte[] phoneCipher,
            byte[] addressCipher,
            BigDecimal lng,
            BigDecimal lat,
            LocalTime businessStart,
            LocalTime businessEnd,
            String timezone,
            int status
    ) {
    }

    public record Therapist(
            long id,
            long staffUserId,
            String employeeNo,
            String name,
            long homeStoreId,
            String level,
            String avatarUrl,
            String intro,
            int ratingX100,
            int status,
            List<Long> projectIds,
            List<Long> symptomIds
    ) {
    }

    public record Project(
            long id,
            String code,
            String name,
            int durationMinutes,
            int bufferMinutes,
            long priceFen,
            String description,
            String coverUrl,
            int status
    ) {
    }

    public record StoreProject(long storeId, long projectId, Long priceFen, int status) {
    }

    public record Symptom(long id, Long parentId, String type, String name, int sortNo, int status) {
    }

    public record SymptomProject(long symptomId, long projectId) {
    }

    public record Room(long id, long storeId, String name, int sortNo, int status) {
    }

    public record Bed(long id, long storeId, long roomId, String name, int sortNo, int status) {
    }

    public record ScheduleTemplate(
            long therapistId,
            long storeId,
            int weekday,
            LocalTime startTime,
            LocalTime endTime,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            int status
    ) {
    }
}
