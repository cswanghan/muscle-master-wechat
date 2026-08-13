package com.jisuodashi.auth;

import com.jisuodashi.common.AppProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Clock;

@Configuration
public class WeChatClientConfig {

    @Bean
    @ConditionalOnMissingBean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public WeChatClient weChatClient(AppProperties properties, RestClient.Builder builder, Clock clock) {
        AppProperties.Wechat wechat = properties.getWechat();
        if (wechat.isMock()) {
            return new MockWeChatClient();
        }
        if (blank(wechat.getCustomerAppId()) || blank(wechat.getCustomerAppSecret())) {
            throw new IllegalStateException(
                    "wechat.mock=false requires app.wechat.customer-app-id and customer-app-secret");
        }
        return new HttpWeChatClient(properties, builder, clock);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
