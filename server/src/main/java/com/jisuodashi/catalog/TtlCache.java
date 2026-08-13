package com.jisuodashi.catalog;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class TtlCache<K, V> {

    private final Duration ttl;
    private final Clock clock;
    private final ConcurrentHashMap<K, Entry<V>> map = new ConcurrentHashMap<>();

    public TtlCache(Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    public V get(K key, Supplier<V> loader) {
        long now = clock.millis();
        Entry<V> hit = map.get(key);
        if (hit != null && hit.expireAt > now) {
            return hit.value;
        }
        V loaded = loader.get();
        map.put(key, new Entry<>(loaded, now + ttl.toMillis()));
        return loaded;
    }

    private record Entry<V>(V value, long expireAt) {
    }
}
