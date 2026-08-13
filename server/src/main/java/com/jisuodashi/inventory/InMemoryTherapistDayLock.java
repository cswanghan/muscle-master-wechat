package com.jisuodashi.inventory;

import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;

/** Fallback when Redis is off (dev / tests). Same NX + 5s TTL semantics. */
public final class InMemoryTherapistDayLock implements TherapistDayLock {

    private final ConcurrentHashMap<String, Long> locks = new ConcurrentHashMap<>();
    private final java.util.function.LongSupplier nowMs;

    public InMemoryTherapistDayLock() {
        this(System::currentTimeMillis);
    }

    public InMemoryTherapistDayLock(java.util.function.LongSupplier nowMs) {
        this.nowMs = nowMs;
    }

    @Override
    public boolean tryAcquire(long therapistId, LocalDate date) {
        String key = TherapistDayLock.key(therapistId, date);
        long now = nowMs.getAsLong();
        long expireAt = now + TTL_SECONDS * 1000L;
        Long existing = locks.putIfAbsent(key, expireAt);
        if (existing == null) {
            return true;
        }
        if (existing > now) {
            return false;
        }
        return locks.replace(key, existing, expireAt);
    }

    @Override
    public void release(long therapistId, LocalDate date) {
        locks.remove(TherapistDayLock.key(therapistId, date));
    }
}
