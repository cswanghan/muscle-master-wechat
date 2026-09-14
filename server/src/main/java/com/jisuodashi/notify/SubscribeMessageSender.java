package com.jisuodashi.notify;

/**
 * 订阅消息发送口。dev 用 {@link LoggingSubscribeMessageSender} 打日志，
 * 接了真模板再换实现 —— 先把额度与调度跑通，发送这一步是最后一公里。
 */
public interface SubscribeMessageSender {

    /**
     * @return true 表示已投递；false 表示可重试的失败（额度还在）。
     */
    boolean send(long customerId, String templateId, String page, java.util.Map<String, String> data);
}
