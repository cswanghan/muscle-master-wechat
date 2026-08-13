package com.jisuodashi.inventory;

import java.time.LocalDate;

/** Redis {@code SET NX EX 5} on {@code lock:slot:{therapistId}:{date}}. In-memory on dev. */
public interface TherapistDayLock {

    int TTL_SECONDS = 5;

    boolean tryAcquire(long therapistId, LocalDate date);

    void release(long therapistId, LocalDate date);

    static String key(long therapistId, LocalDate date) {
        return "lock:slot:" + therapistId + ":" + date;
    }
}
