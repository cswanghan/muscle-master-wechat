package com.jisuodashi.auth;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.ErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.util.Map;

public class HttpWeChatClient implements WeChatClient {

    private static final Logger log = LoggerFactory.getLogger(HttpWeChatClient.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    private final AppProperties.Wechat wechat;
    private final RestClient rest;
    private final Clock clock;
    private volatile String cachedToken;
    private volatile long tokenExpireEpoch;

    public HttpWeChatClient(AppProperties properties, RestClient.Builder builder, Clock clock) {
        this.wechat = properties.getWechat();
        this.rest = builder.baseUrl("https://api.weixin.qq.com").build();
        this.clock = clock;
    }

    @Override
    public WeChatSession code2Session(String code, WeChatApp app) {
        if (code == null || code.isBlank()) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "code 不能为空");
        }
        String appId = appId(app);
        String secret = secret(app);
        Map<String, Object> body = rest.get()
                .uri("/sns/jscode2session?appid={appid}&secret={secret}&js_code={code}&grant_type=authorization_code",
                        appId, secret, code)
                .retrieve()
                .body(MAP);
        if (body == null) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "微信登录失败");
        }
        assertNoErr(body, "微信登录失败");
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
        String token = accessToken(app);
        Map<String, Object> body = rest.post()
                .uri("/wxa/business/getuserphonenumber?access_token={token}", token)
                .body(Map.of("code", phoneCode))
                .retrieve()
                .body(MAP);
        if (body == null) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "微信手机号获取失败");
        }
        assertNoErr(body, "微信手机号获取失败");
        Object info = body.get("phone_info");
        if (!(info instanceof Map<?, ?> phoneInfo)) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "微信手机号获取失败");
        }
        Object pure = phoneInfo.get("purePhoneNumber");
        if (pure == null || String.valueOf(pure).isBlank()) {
            Object full = phoneInfo.get("phoneNumber");
            if (full == null || String.valueOf(full).isBlank()) {
                throw new ApiException(ErrorCodes.BAD_REQUEST, "微信手机号获取失败");
            }
            return String.valueOf(full);
        }
        return String.valueOf(pure);
    }

    private String accessToken(WeChatApp app) {
        long now = clock.instant().getEpochSecond();
        if (cachedToken != null && now < tokenExpireEpoch - 60) {
            return cachedToken;
        }
        Map<String, Object> body = rest.get()
                .uri("/cgi-bin/token?grant_type=client_credential&appid={appid}&secret={secret}",
                        appId(app), secret(app))
                .retrieve()
                .body(MAP);
        if (body == null) {
            throw new ApiException(ErrorCodes.INTERNAL, "微信 access_token 获取失败");
        }
        assertNoErr(body, "微信 access_token 获取失败");
        Object token = body.get("access_token");
        if (token == null || String.valueOf(token).isBlank()) {
            throw new ApiException(ErrorCodes.INTERNAL, "微信 access_token 获取失败");
        }
        int expires = 7200;
        Object exp = body.get("expires_in");
        if (exp instanceof Number n) {
            expires = n.intValue();
        }
        cachedToken = String.valueOf(token);
        tokenExpireEpoch = now + expires;
        return cachedToken;
    }

    private String appId(WeChatApp app) {
        String value = app == WeChatApp.STAFF ? wechat.getStaffAppId() : wechat.getCustomerAppId();
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCodes.INTERNAL, "微信 AppID 未配置");
        }
        return value;
    }

    private String secret(WeChatApp app) {
        String value = app == WeChatApp.STAFF ? wechat.getStaffAppSecret() : wechat.getCustomerAppSecret();
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCodes.INTERNAL, "微信 AppID 未配置");
        }
        return value;
    }

    private static void assertNoErr(Map<String, Object> body, String message) {
        Object err = body.get("errcode");
        if (err instanceof Number n && n.intValue() != 0) {
            log.warn("wechat errcode={} errmsg={}", err, body.get("errmsg"));
            throw new ApiException(ErrorCodes.BAD_REQUEST, message);
        }
    }
}
