package com.jisuodashi.auth;

import java.util.List;

public record JwtPrincipal(
        long subjectId,
        TokenType typ,
        Long customerId,
        Long staffId,
        String scopeType,
        List<Long> storeIds
) {
    public static JwtPrincipal customer(long customerId) {
        return new JwtPrincipal(customerId, TokenType.C, customerId, null, null, List.of());
    }

    public static JwtPrincipal staff(long staffId, TokenType typ, String scopeType, List<Long> storeIds) {
        return new JwtPrincipal(staffId, typ, null, staffId, scopeType, List.copyOf(storeIds));
    }
}
