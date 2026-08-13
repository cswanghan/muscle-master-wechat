package com.jisuodashi.rbac;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StoreScopeSqlInterceptorTest {

    @Test
    void skipUnknownHandlerIsFalse() {
        assertThat(StoreScopeSqlInterceptor.skipMapper(new Object())).isFalse();
        assertThat(StoreScopeSqlInterceptor.mappedStatementId(new Object())).isNull();
    }
}
