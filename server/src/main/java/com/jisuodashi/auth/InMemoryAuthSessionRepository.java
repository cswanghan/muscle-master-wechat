package com.jisuodashi.auth;

import org.springframework.stereotype.Repository;

import java.util.concurrent.CopyOnWriteArrayList;

@Repository
public class InMemoryAuthSessionRepository implements AuthSessionRepository {

    private final CopyOnWriteArrayList<AuthSession> sessions = new CopyOnWriteArrayList<>();

    @Override
    public void insert(AuthSession session) {
        sessions.add(session);
    }

    @Override
    public void clear() {
        sessions.clear();
    }
}
