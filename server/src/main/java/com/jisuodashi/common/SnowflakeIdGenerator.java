package com.jisuodashi.common;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/** 41/10/12 snowflake. Worker comes from {@code SNOWFLAKE_WORKER_ID} (D20). */
@Component
public class SnowflakeIdGenerator {

    /** 2026-01-01 00:00:00 Asia/Shanghai, so generated ids stay close to demo fixtures. */
    static final long EPOCH_MS = Instant.parse("2025-12-31T16:00:00Z").toEpochMilli();

    private static final long WORKER_BITS = 10;
    private static final long SEQ_BITS = 12;
    private static final long MAX_WORKER = (1L << WORKER_BITS) - 1;
    private static final long MAX_SEQ = (1L << SEQ_BITS) - 1;

    private final long workerId;
    private final AtomicLong lastMsAndSeq = new AtomicLong(0);

    public SnowflakeIdGenerator(AppProperties properties) {
        long id = properties.getSnowflake().getWorkerId();
        if (id < 0 || id > MAX_WORKER) {
            throw new IllegalArgumentException("snowflake worker-id out of range: " + id);
        }
        this.workerId = id;
    }

    public synchronized long nextId() {
        long now = Math.max(System.currentTimeMillis(), EPOCH_MS);
        long packed = lastMsAndSeq.get();
        long lastMs = packed >>> SEQ_BITS;
        long seq = packed & MAX_SEQ;
        if (now < lastMs) {
            now = lastMs;
        }
        if (now == lastMs) {
            seq = (seq + 1) & MAX_SEQ;
            if (seq == 0) {
                now++;
            }
        } else {
            seq = 0;
        }
        lastMsAndSeq.set((now << SEQ_BITS) | seq);
        return ((now - EPOCH_MS) << (WORKER_BITS + SEQ_BITS)) | (workerId << SEQ_BITS) | seq;
    }
}
