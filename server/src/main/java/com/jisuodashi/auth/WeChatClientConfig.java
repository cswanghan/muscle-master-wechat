package com.jisuodashi.auth;

import com.jisuodashi.common.AppProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class WeChatClientConfig {

    @Bean
    @ConditionalOnMissingBean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public WeChatClient weChatClient(AppProperties properties, RestClient.Builder builder) {
        if (properties.getWechat().isMock()) {
            return new MockWeChatClient();
        }
        return new HttpWeChatClient(properties, builder);
    }
}
