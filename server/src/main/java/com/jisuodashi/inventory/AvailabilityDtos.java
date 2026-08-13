package com.jisuodashi.inventory;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public final class AvailabilityDtos {

    private AvailabilityDtos() {
    }

    public record Availability(
            String storeId,
            String date,
            String projectId,
            int slotMinutes,
            int occupySlots,
            List<Therapist> therapists
    ) {
    }

    public record Therapist(
            String therapistId,
            String name,
            String level,
            int ratingX100,
            List<Start> starts,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<Block> blocks
    ) {
    }

    public record Start(int slotNo, String start, long priceFen) {
    }

    /** Calendar color only. LOCKED/BOOKED/REST are never starts. */
    public record Block(int slotNo, String start, String state) {
    }
}
