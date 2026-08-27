package com.jisuodashi.db;

import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Boots the app the way it ships: the {@code @Profile("!dev")} MyBatis/JDBC stores on real InnoDB,
 * schema built by Flyway V1–V5, demo store seeded by V3.
 *
 * <p>The rest of the suite runs the {@code dev} in-memory stores, so ordered {@code FOR UPDATE},
 * the {@code uk_occ} anti-oversell key and deadlock retry — the whole reason the persistence layer
 * is written the way it is — had no coverage at all. Tests wearing this annotation close that.
 *
 * <p>Skipped, not failed, without Docker: the README warns a dev box may not have it, and a laptop
 * should not go red over an absent daemon. CI runs {@code ubuntu-latest}, which does have Docker,
 * so the gate is real where it counts.
 *
 * <p>An annotation rather than a base class because {@link EnabledIf} is not {@code @Inherited} —
 * extending a guarded superclass would silently drop the guard, which is worse than not having one.
 *
 * <p>Redis is excluded in {@code application-mysqlit.yml}, so {@code TherapistDayLockConfig} falls
 * back to the in-process therapist-day lock. That is intentional: the day lock is a pre-filter, and
 * keeping it in-process pushes more of each race down to the database, which is the layer on trial.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "app.clock.fixed-at=" + MysqlBackedTest.FIXED_AT)
@ActiveProfiles("mysqlit")
@ContextConfiguration(initializers = MysqlTestDatabase.Datasource.class)
@EnabledIf("com.jisuodashi.db.MysqlTestDatabase#dockerAvailable")
public @interface MysqlBackedTest {

    /** Same demo-calendar anchor the dev suite pins, so fixtures read alike across profiles. */
    String FIXED_AT = "2026-08-14T19:00:00";
}
