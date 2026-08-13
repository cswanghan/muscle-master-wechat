package com.jisuodashi.auth;

public interface WeChatClient {

    WeChatSession code2Session(String code, WeChatApp app);

    String phoneFromCode(String phoneCode, WeChatApp app);
}
