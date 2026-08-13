package com.jisuodashi.rbac;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an /f or /a handler as store-scoped. Writes without this fail the CI arch
 * test and are rejected at runtime so a missing annotation cannot leak other stores.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface StoreScoped {
}
