package com.jisuodashi.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
@Profile("dev")
public class InMemoryAuthSessionRepository implements AuthSessionRepository {

    private final CopyOnWriteArrayList<AuthSession> sessions = new CopyOnWriteArrayList<>();

    @Override
    public void insert(AuthSession session) {
        sessions.add(session);
    }

    @Override
    public void reassignCustomer(long fromCustomerId, long toCustomerId) {
        for (AuthSession session : sessions) {
            if ("CUSTOMER".equals(session.getSubjectType()) && session.getSubjectId() == fromCustomerId) {
                session.setSubjectId(toCustomerId);
            }
        }
    }

    @Override
    public List<AuthSession> findBySubject(String subjectType, long subjectId) {
        return sessions.stream()
                .filter(s -> subjectType.equals(s.getSubjectType()) && s.getSubjectId() == subjectId)
                .toList();
    }

    @Override
    public void clear() {
        sessions.clear();
    }
}
