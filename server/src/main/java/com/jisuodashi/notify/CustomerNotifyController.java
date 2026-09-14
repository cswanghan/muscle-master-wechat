package com.jisuodashi.notify;

import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订阅额度回收。前端 {@code wx.requestSubscribeMessage} 拿到「允许」后调这里。
 *
 * <p>一次授权换一条额度，推完即销 —— 这是微信的规则，不是我们的节流。
 */
@RestController
@RequestMapping("/api/v1/c/notify")
public class CustomerNotifyController {

    private final NotifyService notify;

    public CustomerNotifyController(NotifyService notify) {
        this.notify = notify;
    }

    @PostMapping("/grants")
    public ApiResponse<NotifyDtos.GrantResponse> grant(
            @RequestBody(required = false) NotifyDtos.GrantRequest request) {
        return ApiResponse.ok(notify.grant(
                AuthContext.requireCustomer().customerId(),
                request == null ? null : request.templateId(),
                request == null ? null : request.orderId()));
    }
}
