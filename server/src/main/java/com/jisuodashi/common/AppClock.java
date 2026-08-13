package com.jisuodashi.common;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Business calendar is always Asia/Shanghai (D10). */
@Component
public class AppClock {

    public static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final Clock clock;

    public AppClock() {
        this(Clock.system(SHANGHAI));
    }

    public AppClock(Clock clock) {
        this.clock = clock.withZone(SHANGHAI);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public Instant instant() {
        return clock.instant();
    }

    public Clock clock() {
        return clock;
    }
}
