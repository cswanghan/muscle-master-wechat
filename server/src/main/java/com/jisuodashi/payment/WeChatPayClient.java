package com.jisuodashi.payment;

import java.util.Map;

/** Direct-merchant (D17) JSAPI prepay + notify verify. Dev uses {@link MockWeChatPayClient}. */
public interface WeChatPayClient {

    record Prepay(String prepayId) {
    }

    Prepay jsapiPrepay(String paymentNo, long amountFen, String description);

    Map<String, String> resign(String prepayId);

    WeChatNotify parseAndVerify(String body, Map<String, String> headers);
}
