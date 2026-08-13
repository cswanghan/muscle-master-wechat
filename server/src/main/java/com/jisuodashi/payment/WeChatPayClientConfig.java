package com.jisuodashi.payment;

import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WeChatPayClientConfig {

    @Bean
    public WeChatPayClient weChatPayClient(AppProperties properties, AppClock clock) {
        if (properties.getWechat().isMock()) {
            return new MockWeChatPayClient(clock);
        }
        return new UnconfiguredWeChatPayClient();
    }
}
