package com.jisuodashi.auth;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;

/**
 * Dev / test code2session. Real AppID is not required. Do not persist these openids as
 * walk-in substitutes.
 */
public class MockWeChatClient implements WeChatClient {

    public static final String DEV_CODE = "dev";
    public static final String DEV_STAFF_CODE = "dev-staff";
    public static final String DEV_STAFF_MANAGER_CODE = "dev-staff-manager";
    public static final String DEV_STAFF_FRONT_CODE = "dev-staff-front";
    public static final String DEV_STAFF_T1_CODE = "dev-staff-t1";
    public static final String DEV_C2_CODE = "dev-c2";
    public static final String DEV_PHONE_CODE = "dev-phone";
    public static final String DEV_PHONE_CODE_2 = "dev-phone-2";

    public static final String DEV_CUSTOMER_OPENID = "oDEV_C";
    public static final String DEV_CUSTOMER_UNIONID = "uDEV_C";
    public static final String DEV_CUSTOMER2_OPENID = "oDEV_C2";
    public static final String DEV_STAFF_OPENID = "oDEV_STAFF";
    public static final String DEV_MANAGER_OPENID = "oDEV_MANAGER";
    public static final String DEV_FRONT_OPENID = "oDEV_FRONT";
    public static final String DEV_T1_OPENID = "oDEV_T1";
    public static final String DEV_PHONE = "13800138000";
    public static final String DEV_PHONE_2 = "13900139000";

    @Override
    public WeChatSession code2Session(String code, WeChatApp app) {
        if (code == null || code.isBlank()) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "code 不能为空");
        }
        return switch (code) {
            case DEV_CODE -> new WeChatSession(DEV_CUSTOMER_OPENID, DEV_CUSTOMER_UNIONID);
            case DEV_C2_CODE -> new WeChatSession(DEV_CUSTOMER2_OPENID, "uDEV_C2");
            case DEV_STAFF_CODE -> new WeChatSession(DEV_STAFF_OPENID, "uDEV_STAFF");
            case DEV_STAFF_MANAGER_CODE -> new WeChatSession(DEV_MANAGER_OPENID, "uDEV_MANAGER");
            case DEV_STAFF_FRONT_CODE -> new WeChatSession(DEV_FRONT_OPENID, "uDEV_FRONT");
            case DEV_STAFF_T1_CODE -> new WeChatSession(DEV_T1_OPENID, "uDEV_T1");
            default -> {
                if (code.startsWith("mock:")) {
                    yield new WeChatSession(code.substring(5), null);
                }
                throw new ApiException(ErrorCodes.BAD_REQUEST, "无效的微信 code");
            }
        };
    }

    @Override
    public String phoneFromCode(String phoneCode, WeChatApp app) {
        if (phoneCode == null || phoneCode.isBlank()) {
            return null;
        }
        return switch (phoneCode) {
            case DEV_PHONE_CODE -> DEV_PHONE;
            case DEV_PHONE_CODE_2 -> DEV_PHONE_2;
            default -> {
                if (phoneCode.startsWith("mock:")) {
                    yield phoneCode.substring(5);
                }
                throw new ApiException(ErrorCodes.BAD_REQUEST, "无效的手机号 code");
            }
        };
    }
}
