package com.jisuodashi.common;

import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class SnowflakeIdGenerator {

    private static final long EPOCH_MS = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
    private static final long WORKER_BITS = 10;
    private static final long SEQ_BITS = 12;
    private static final long MAX_WORKER = (1L << WORKER_BITS) - 1;
    private static final long MAX_SEQ = (1L << SEQ_BITS) - 1;

    private final long workerId;
    private long lastMs = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator(AppProperties properties) {
        long id = properties.getSnowflake().getWorkerId();
        if (id < 0 || id > MAX_WORKER) {
            throw new IllegalStateException("snowflake worker-id out of range");
        }
        this.workerId = id;
    }

    public synchronized long nextId() {
        long now = System.currentTimeMillis();
        if (now < lastMs) {
            now = lastMs;
        }
        if (now == lastMs) {
            sequence = (sequence + 1) & MAX_SEQ;
            if (sequence == 0) {
                now = lastMs + 1;
            }
        } else {
            sequence = 0;
        }
        lastMs = now;
        return ((now - EPOCH_MS) << (WORKER_BITS + SEQ_BITS)) | (workerId << SEQ_BITS) | sequence;
    }
}
