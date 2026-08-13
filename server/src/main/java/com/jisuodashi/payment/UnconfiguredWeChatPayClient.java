package com.jisuodashi.payment;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;

import java.util.Map;

/** Production placeholder until APIv3 certs (wechat-onboarding) land. */
public class UnconfiguredWeChatPayClient implements WeChatPayClient {

    @Override
    public Prepay jsapiPrepay(String paymentNo, long amountFen, String description) {
        throw new ApiException(ErrorCodes.CHANNEL_ERROR, "支付渠道未配置");
    }

    @Override
    public Map<String, String> resign(String prepayId) {
        throw new ApiException(ErrorCodes.CHANNEL_ERROR, "支付渠道未配置");
    }

    @Override
    public WeChatNotify parseAndVerify(String body, Map<String, String> headers) {
        throw new ApiException(ErrorCodes.CHANNEL_ERROR, "支付渠道未配置");
    }
}
