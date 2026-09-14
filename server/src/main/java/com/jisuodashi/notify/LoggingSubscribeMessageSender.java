package com.jisuodashi.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 演示与自测用：只打日志，不真发。
 *
 * <p>接真模板需要在 MP 后台申请模板 ID 并拿到 {@code access_token}，
 * 属于上线前的配置动作；在此之前用它把额度消耗与调度链路跑通。
 *
 * <p>接真实现时给那个 bean 加 {@code @Primary} 即可覆盖本类 ——
 * 不用 {@code @ConditionalOnMissingBean}，它在 {@code @Component} 上不生效
 * （那是给 {@code @Bean} 方法的），会把自己也排除掉。
 */
@Component
public class LoggingSubscribeMessageSender implements SubscribeMessageSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSubscribeMessageSender.class);

    @Override
    public boolean send(long customerId, String templateId, String page, Map<String, String> data) {
        log.info("subscribe-message customer={} template={} page={} data={}",
                customerId, templateId, page, data);
        return true;
    }
}
