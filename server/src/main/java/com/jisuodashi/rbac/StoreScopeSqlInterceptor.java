package com.jisuodashi.rbac;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.springframework.stereotype.Component;

import java.sql.Connection;

/** Rewrites the BoundSql the driver will run. Job SQL has no StoreScope and is left alone. */
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
            BoundSql boundSql = handler.getBoundSql();
            String next = SqlScopeRewriter.rewrite(boundSql.getSql(), scope);
            if (!next.equals(boundSql.getSql())) {
                MetaObject meta = SystemMetaObject.forObject(boundSql);
                meta.setValue("sql", next);
            }
        }
        return invocation.proceed();
    }
}
