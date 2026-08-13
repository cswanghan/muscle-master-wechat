package com.jisuodashi.inventory;

import java.time.LocalDate;
import java.util.List;

public record SlotGenerateResult(
        LocalDate today,
        LocalDate from,
        LocalDate to,
        boolean firstRun,
        int therapistInserted,
        int therapistIgnored,
        int bedInserted,
        int bedIgnored,
        int restWritten,
        int freeWritten,
        int conflicts,
        int humanTasks,
        List<SampleDay> samples
) {

    public record SampleDay(
            long therapistId,
            String therapistName,
            LocalDate date,
            int free,
            int rest,
            long storeId
    ) {
    }
}
