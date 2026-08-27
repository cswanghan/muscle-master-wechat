package com.jisuodashi.employment;

import java.time.Instant;
import java.time.LocalDate;

public final class EmploymentModels {

    private EmploymentModels() {
    }

    public record Log(
            long id,
            long staffId,
            String action,
            LocalDate effectiveOn,
            String reason,
            Long operatorId,
            Instant createdAt
    ) {
    }

    public static final String ONBOARD = "ONBOARD";
    public static final String OFFBOARD = "OFFBOARD";
    public static final String REHIRE = "REHIRE";
}
