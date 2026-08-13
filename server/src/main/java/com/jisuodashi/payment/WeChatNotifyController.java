package com.jisuodashi.payment;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

/**
 * WeChat APIv3 notify. No JWT — verified (or mock-parsed) then {@code onWechatNotify}.
 * Response is the APIv3 envelope, not {@code ApiResponse}.
 */
@RestController
@RequestMapping("/api/v1/pay/wechat")
public class WeChatNotifyController {

    private static final Logger log = LoggerFactory.getLogger(WeChatNotifyController.class);

    private final PaymentService payments;

    public WeChatNotifyController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping(value = "/notify", produces = MediaType.APPLICATION_JSON_VALUE)
    public PaymentDtos.WechatNotifyAck notify(@RequestBody JsonNode body, HttpServletRequest request) {
        try {
            return payments.onWechatNotify(body.toString(), headers(request));
        } catch (RuntimeException ex) {
            log.warn("wechat notify rejected: {}", ex.getMessage());
            return PaymentDtos.WechatNotifyAck.fail(ex.getMessage());
        }
    }

    private static Map<String, String> headers(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) {
            return headers;
        }
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }
}
