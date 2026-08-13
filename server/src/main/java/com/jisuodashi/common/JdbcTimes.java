package com.jisuodashi.common;

import java.sql.Timestamp;
import java.time.Instant;

public final class JdbcTimes {

    private JdbcTimes() {
    }

    public static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    public static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
