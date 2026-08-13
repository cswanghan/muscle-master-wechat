package com.jisuodashi.inventory;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class RedisTherapistDayLock implements TherapistDayLock {

    private static final DefaultRedisScript<Long> UNLOCK = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    public RedisTherapistDayLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public String tryAcquire(long therapistId, LocalDate date) {
        try {
            String token = UUID.randomUUID().toString();
            Boolean ok = redis.opsForValue().setIfAbsent(
                    TherapistDayLock.key(therapistId, date), token, Duration.ofSeconds(TTL_SECONDS));
            return Boolean.TRUE.equals(ok) ? token : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    @Override
    public void release(long therapistId, LocalDate date, String token) {
        if (token == null) {
            return;
        }
        try {
            redis.execute(UNLOCK, List.of(TherapistDayLock.key(therapistId, date)), token);
        } catch (RuntimeException ignored) {
            // Best-effort; TTL still expires the key.
        }
    }
}
