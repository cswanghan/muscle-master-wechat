package com.jisuodashi.inventory;

import java.time.LocalDate;

/** Redis {@code SET NX EX 5} on {@code lock:slot:{therapistId}:{date}}. In-memory on dev. */
public interface TherapistDayLock {

    int TTL_SECONDS = 5;

    /**
     * @return owner token if acquired, otherwise {@code null}
     */
    String tryAcquire(long therapistId, LocalDate date);

    /** Compare-and-delete: only the token holder may release. */
    void release(long therapistId, LocalDate date, String token);

    static String key(long therapistId, LocalDate date) {
        return "lock:slot:" + therapistId + ":" + date;
    }
}
