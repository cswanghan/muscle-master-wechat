package com.jisuodashi.auth;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.ErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.util.Map;

public class HttpWeChatClient implements WeChatClient {

    private static final Logger log = LoggerFactory.getLogger(HttpWeChatClient.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    private final AppProperties.Wechat wechat;
    private final RestClient rest;

    public HttpWeChatClient(AppProperties properties, RestClient.Builder builder) {
        this.wechat = properties.getWechat();
        this.rest = builder.baseUrl("https://api.weixin.qq.com").build();
    }

    @Override
    public WeChatSession code2Session(String code, WeChatApp app) {
        if (code == null || code.isBlank()) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "code 不能为空");
        }
        String appId = app == WeChatApp.STAFF ? wechat.getStaffAppId() : wechat.getCustomerAppId();
        String secret = app == WeChatApp.STAFF ? wechat.getStaffAppSecret() : wechat.getCustomerAppSecret();
        if (appId == null || appId.isBlank() || secret == null || secret.isBlank()) {
            throw new ApiException(ErrorCodes.INTERNAL, "微信 AppID 未配置");
        }
        Map<String, Object> body = rest.get()
                .uri("/sns/jscode2session?appid={appid}&secret={secret}&js_code={code}&grant_type=authorization_code",
                        appId, secret, code)
                .retrieve()
                .body(MAP);
        if (body == null) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "微信登录失败");
        }
        Object err = body.get("errcode");
        if (err instanceof Number n && n.intValue() != 0) {
            log.warn("code2session errcode={}", err);
            throw new ApiException(ErrorCodes.BAD_REQUEST, "微信登录失败");
        }
        Object openid = body.get("openid");
        if (openid == null || String.valueOf(openid).isBlank()) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "微信登录失败");
        }
        Object unionid = body.get("unionid");
        return new WeChatSession(String.valueOf(openid), unionid == null ? null : String.valueOf(unionid));
    }

    @Override
    public String phoneFromCode(String phoneCode, WeChatApp app) {
        if (phoneCode == null || phoneCode.isBlank()) {
            return null;
        }
        throw new ApiException(ErrorCodes.BAD_REQUEST, "生产环境手机号解密尚未配置 access_token");
    }
}
