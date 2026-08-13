package com.jisuodashi.inventory;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.LocalDate;

public final class RedisTherapistDayLock implements TherapistDayLock {

    private final StringRedisTemplate redis;

    public RedisTherapistDayLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryAcquire(long therapistId, LocalDate date) {
        Boolean ok = redis.opsForValue().setIfAbsent(
                TherapistDayLock.key(therapistId, date), "1", Duration.ofSeconds(TTL_SECONDS));
        return Boolean.TRUE.equals(ok);
    }

    @Override
    public void release(long therapistId, LocalDate date) {
        redis.delete(TherapistDayLock.key(therapistId, date));
    }
}
