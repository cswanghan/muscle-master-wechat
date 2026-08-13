package com.jisuodashi.inventory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class TherapistDayLockConfig {

    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    TherapistDayLock redisTherapistDayLock(StringRedisTemplate redis) {
        return new RedisTherapistDayLock(redis);
    }

    @Bean
    @ConditionalOnMissingBean(TherapistDayLock.class)
    TherapistDayLock inMemoryTherapistDayLock() {
        return new InMemoryTherapistDayLock();
    }
}
