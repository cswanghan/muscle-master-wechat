package com.jisuodashi.inventory;

import com.jisuodashi.common.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * 30s store+date cache. lockNew / release must {@link #invalidate}.
 * In-memory is enough on dev; Redis keys {@code cache:avail:{storeId}:{date}:*}
 * are deleted when a template is present.
 */
@Component
public class AvailabilityCache {

    public static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

    private final Duration ttl;
    private final Clock clock;
    private final StringRedisTemplate redis;
    private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> generations = new ConcurrentHashMap<>();
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    @Autowired
    public AvailabilityCache(
            AppProperties properties,
            Clock clock,
            @Autowired(required = false) StringRedisTemplate redis
    ) {
        this(properties.getAvailability().getCacheTtl(), clock, redis);
    }

    public AvailabilityCache(Duration ttl, Clock clock) {
        this(ttl, clock, null);
    }

    public AvailabilityCache(Duration ttl, Clock clock, StringRedisTemplate redis) {
        this.ttl = ttl == null ? DEFAULT_TTL : ttl;
        this.clock = clock;
        this.redis = redis;
    }

    public AvailabilityDay get(long storeId, LocalDate date, Supplier<AvailabilityDay> loader) {
        String key = key(storeId, date);
        long now = clock.millis();
        Entry hit = map.get(key);
        if (hit != null && hit.expireAt > now) {
            hits.increment();
            return hit.value;
        }
        misses.increment();
        long stamp = generationOf(key);
        AvailabilityDay loaded = loader.get();
        // Invalidate during load: do not put the pre-write snapshot back.
        map.compute(key, (k, existing) -> {
            if (generationOf(k) != stamp) {
                return existing;
            }
            return new Entry(loaded, clock.millis() + ttl.toMillis());
        });
        return loaded;
    }

    /** Drop this store+date (all project/therapist views share the key). */
    public void invalidate(long storeId, LocalDate date) {
        String key = key(storeId, date);
        generations.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        map.remove(key);
        evictRedis(storeId, date);
    }

    long generation(long storeId, LocalDate date) {
        return generationOf(key(storeId, date));
    }

    private long generationOf(String key) {
        return generations.computeIfAbsent(key, k -> new AtomicLong()).get();
    }

    public boolean contains(long storeId, LocalDate date) {
        Entry hit = map.get(key(storeId, date));
        return hit != null && hit.expireAt > clock.millis();
    }

    public long hits() {
        return hits.sum();
    }

    public long misses() {
        return misses.sum();
    }

    public int size() {
        return map.size();
    }

    public static String key(long storeId, LocalDate date) {
        return storeId + ":" + date;
    }

    public static String redisPattern(long storeId, LocalDate date) {
        return "cache:avail:" + storeId + ":" + date + ":*";
    }

    private void evictRedis(long storeId, LocalDate date) {
        if (redis == null) {
            return;
        }
        try {
            ScanOptions opts = ScanOptions.scanOptions()
                    .match(redisPattern(storeId, date))
                    .count(64)
                    .build();
            Set<String> keys = new HashSet<>();
            try (Cursor<String> cursor = redis.scan(opts)) {
                while (cursor.hasNext()) {
                    keys.add(cursor.next());
                }
            }
            if (!keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (RuntimeException ignored) {
            // Best-effort: next TTL miss reloads.
        }
    }

    private record Entry(AvailabilityDay value, long expireAt) {
    }
}
