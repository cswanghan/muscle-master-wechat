package com.jisuodashi.auth;

import java.util.List;

public interface AuthSessionRepository {

    void insert(AuthSession session);

    /** D19: rewrite C sessions from the dying openid row onto survivor B. */
    void reassignCustomer(long fromCustomerId, long toCustomerId);

    List<AuthSession> findBySubject(String subjectType, long subjectId);

    default void clear() {
    }
}
