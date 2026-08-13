package com.jisuodashi.rbac;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.springframework.stereotype.Component;

import java.sql.Connection;

/** Rewrites BoundSql for /f /a requests. Job mappers are left alone even if a scope leaked. */
@Component
@Intercepts({
        @Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class})
})
public class StoreScopeSqlInterceptor implements Interceptor {

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        StoreScope scope = StoreScopeContext.get();
        if (scope != null && !scope.all()) {
            StatementHandler handler = (StatementHandler) invocation.getTarget();
            if (!skipMapper(handler)) {
                BoundSql boundSql = handler.getBoundSql();
                String next = SqlScopeRewriter.rewrite(boundSql.getSql(), scope);
                if (!next.equals(boundSql.getSql())) {
                    MetaObject meta = SystemMetaObject.forObject(boundSql);
                    meta.setValue("sql", next);
                }
            }
        }
        return invocation.proceed();
    }

    static boolean skipMapper(Object handler) {
        String id = mappedStatementId(handler);
        return id != null && id.contains("InventoryGenerateMapper");
    }

    static String mappedStatementId(Object handler) {
        MetaObject meta = SystemMetaObject.forObject(handler);
        for (String path : new String[] {"delegate.mappedStatement", "mappedStatement"}) {
            try {
                Object value = meta.getValue(path);
                if (value instanceof MappedStatement ms) {
                    return ms.getId();
                }
            } catch (Exception ignored) {
                // plugin target shape varies
            }
        }
        return null;
    }
}
