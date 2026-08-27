package com.jisuodashi.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Configuration
public class ClockConfig {

    public static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /**
     * One time source for the whole app — {@link AppClock} wraps this same bean, so freezing
     * here freezes everything. {@code app.clock.fixed-at} is empty in production and local dev
     * (system clock); the test harness pins it to the demo calendar anchor.
     */
    @Bean
    public Clock clock(AppProperties properties) {
        String fixedAt = properties.getClock().getFixedAt();
        if (fixedAt == null || fixedAt.isBlank()) {
            return Clock.system(SHANGHAI);
        }
        return Clock.fixed(
                LocalDateTime.parse(fixedAt.trim()).atZone(SHANGHAI).toInstant(), SHANGHAI);
    }
}
