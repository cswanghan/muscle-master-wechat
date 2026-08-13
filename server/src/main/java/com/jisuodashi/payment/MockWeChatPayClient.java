package com.jisuodashi.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.ErrorCodes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Dev / test prepay. No merchant cert, no HTTP. Notify body is the decrypted
 * payload (or a flat {@code out_trade_no}/{@code amount_fen} mock).
 */
public class MockWeChatPayClient implements WeChatPayClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AppClock clock;

    public MockWeChatPayClient(AppClock clock) {
        this.clock = clock;
    }

    @Override
    public Prepay jsapiPrepay(String paymentNo, long amountFen, String description) {
        if (paymentNo == null || paymentNo.isBlank()) {
            throw new ApiException(ErrorCodes.PREPAY_FAILED, "预支付失败");
        }
        return new Prepay(prepayId(paymentNo));
    }

    @Override
    public Map<String, String> resign(String prepayId) {
        String ts = String.valueOf(clock.instant().getEpochSecond());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String pkg = "prepay_id=" + prepayId;
        Map<String, String> params = new LinkedHashMap<>();
        params.put("timeStamp", ts);
        params.put("nonceStr", nonce);
        params.put("package", pkg);
        params.put("signType", "RSA");
        params.put("paySign", mockSign(ts, nonce, pkg));
        return params;
    }

    @Override
    public WeChatNotify parseAndVerify(String body, Map<String, String> headers) {
        if (body == null || body.isBlank()) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "回调体为空");
        }
        try {
            JsonNode root = JSON.readTree(body);
            String outTradeNo = text(root, "out_trade_no");
            String txn = text(root, "transaction_id");
            if (txn == null) {
                txn = "mock_txn_" + (outTradeNo == null ? "unknown" : outTradeNo);
            }
            long amount = amountFen(root);
            if (outTradeNo == null || outTradeNo.isBlank()) {
                throw new ApiException(ErrorCodes.BAD_REQUEST, "缺少 out_trade_no");
            }
            return new WeChatNotify(outTradeNo, txn, amount, body);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "回调体无效");
        }
    }

    static String prepayId(String paymentNo) {
        return "mock_prepay_" + paymentNo;
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static long amountFen(JsonNode root) {
        JsonNode flat = root.get("amount_fen");
        if (flat != null && flat.canConvertToLong()) {
            return flat.asLong();
        }
        JsonNode amount = root.get("amount");
        if (amount != null && amount.has("total")) {
            return amount.get("total").asLong();
        }
        throw new ApiException(ErrorCodes.BAD_REQUEST, "缺少金额");
    }

    private static String mockSign(String ts, String nonce, String pkg) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((ts + "\n" + nonce + "\n" + pkg).getBytes(StandardCharsets.UTF_8));
            return "MOCK_" + HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            return "MOCK_SIGN";
        }
    }
}
