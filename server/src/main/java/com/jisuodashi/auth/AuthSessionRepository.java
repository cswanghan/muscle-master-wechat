package com.jisuodashi.auth;

public interface AuthSessionRepository {

    void insert(AuthSession session);

    default void clear() {
    }
}
