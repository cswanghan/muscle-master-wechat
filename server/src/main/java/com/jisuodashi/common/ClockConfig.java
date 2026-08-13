package com.jisuodashi.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class ClockConfig {

    public static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Bean
    public Clock clock() {
        return Clock.system(SHANGHAI);
    }
}
