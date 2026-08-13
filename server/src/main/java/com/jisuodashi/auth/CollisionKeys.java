package com.jisuodashi.auth;

/** V1 {@code human_task.biz_key VARCHAR(64)}. Full HMAC hex is 64 chars; prefix would overflow. */
public final class CollisionKeys {

    public static final String PREFIX = "collide:";
    public static final int MAX_LEN = 64;

    private CollisionKeys() {
    }

    public static String bizKey(String phoneHash) {
        String hash = phoneHash == null ? "" : phoneHash;
        int keep = MAX_LEN - PREFIX.length();
        if (hash.length() > keep) {
            hash = hash.substring(0, keep);
        }
        return PREFIX + hash;
    }
}
