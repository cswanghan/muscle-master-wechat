package com.jisuodashi.payment;

/** Decrypted / mock APIv3 notify payload. {@code outTradeNo} = {@code payment.payment_no}. */
public record WeChatNotify(
        String outTradeNo,
        String transactionId,
        long amountFen,
        String raw
) {
}
