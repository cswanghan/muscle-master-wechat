package com.jisuodashi.payment;

import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WeChatPayClientConfig {

    private static final Logger log = LoggerFactory.getLogger(WeChatPayClientConfig.class);

    @Bean
    public WeChatPayClient weChatPayClient(AppProperties properties, AppClock clock) {
        if (properties.getWechat().isMock()) {
            return new MockWeChatPayClient(clock);
        }
        // Deliberately a warning, not the hard fail WeChatClientConfig uses for login: the APIv3
        // certs are an external dependency (docs/wechat-onboarding.md), and everything except pay
        // must stay runnable while they are outstanding. But an unconfigured channel boots looking
        // healthy and only fails per-request, so say so once at startup rather than leaving ops to
        // discover it from the first CHANNEL_ERROR.
        log.warn("app.wechat.mock=false but no APIv3 credentials are wired: payment and refund will "
                + "fail with CHANNEL_ERROR until the real client lands. See docs/wechat-onboarding.md.");
        return new UnconfiguredWeChatPayClient();
    }
}
