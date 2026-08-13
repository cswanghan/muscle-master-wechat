package com.jisuodashi.inventory;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Fallback when Redis is off (dev / tests). Same NX + 5s TTL + token release. */
public final class InMemoryTherapistDayLock implements TherapistDayLock {

    private final ConcurrentHashMap<String, Lease> locks = new ConcurrentHashMap<>();
    private final java.util.function.LongSupplier nowMs;

    public InMemoryTherapistDayLock() {
        this(System::currentTimeMillis);
    }

    public InMemoryTherapistDayLock(java.util.function.LongSupplier nowMs) {
        this.nowMs = nowMs;
    }

    @Override
    public String tryAcquire(long therapistId, LocalDate date) {
        String key = TherapistDayLock.key(therapistId, date);
        long now = nowMs.getAsLong();
        Lease mine = new Lease(UUID.randomUUID().toString(), now + TTL_SECONDS * 1000L);
        Lease existing = locks.putIfAbsent(key, mine);
        if (existing == null) {
            return mine.token;
        }
        if (existing.expireAt > now) {
            return null;
        }
        return locks.replace(key, existing, mine) ? mine.token : null;
    }

    @Override
    public void release(long therapistId, LocalDate date, String token) {
        if (token == null) {
            return;
        }
        String key = TherapistDayLock.key(therapistId, date);
        Lease have = locks.get(key);
        if (have != null && token.equals(have.token)) {
            locks.remove(key, have);
        }
    }

    private record Lease(String token, long expireAt) {
    }
}
