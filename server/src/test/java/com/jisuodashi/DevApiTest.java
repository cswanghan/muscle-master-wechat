package com.jisuodashi;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Boots the app on the {@code dev} (in-memory) stores with the business calendar frozen at
 * {@link #FIXED_AT}, the same anchor the demo calendar is seeded from.
 *
 * <p>Without the freeze these tests rot: request bodies pin {@code 2026-08-14}, while the
 * context ran on the wall clock, so every date-sensitive guard — {@code CHECK_IN}'s
 * "not-service-day" first among them — started failing the day after the anchor. Time is a
 * fixture here, so pin it exactly like any other fixture.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.clock.fixed-at=2026-08-14T19:00:00")
@ActiveProfiles("dev")
public @interface DevApiTest {

    /** Demo calendar anchor: the Friday evening the state-machine unit tests already pin. */
    String FIXED_AT = "2026-08-14T19:00:00";
}
