package com.jisuodashi.common;

import com.jisuodashi.DevApiTest;
import com.jisuodashi.catalog.DemoCatalogIds;
import com.jisuodashi.inventory.AvailabilityStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The suite pins request bodies to the demo calendar anchor, so the context must pin time too.
 * Left on the wall clock every date-sensitive guard rots the day after the anchor — that is
 * exactly how 26 tests went red and stayed red. These assertions fail loudly instead.
 */
@DevApiTest
class FrozenClockContractTest {

    private static final LocalDateTime ANCHOR = LocalDateTime.parse(DevApiTest.FIXED_AT);

    @Autowired
    private Clock clock;

    @Autowired
    private AppClock appClock;

    @Autowired
    private AvailabilityStore availability;

    @Test
    void clockBeanIsFrozenAtTheDemoAnchor() {
        assertThat(LocalDateTime.now(clock)).isEqualTo(ANCHOR);
    }

    /** Guards the wiring: two uncoordinated time sources is how half a freeze slips through. */
    @Test
    void appClockWrapsTheSameBeanSoFreezingItFreezesEverything() {
        assertThat(appClock.now()).isEqualTo(ANCHOR);
        assertThat(appClock.instant()).isEqualTo(clock.instant());
    }

    /** An anchor with no seeded calendar under it would fail every booking with 40901. */
    @Test
    void demoCalendarIsSeededOnTheFrozenDay() {
        assertThat(appClock.today()).isEqualTo(ANCHOR.toLocalDate());
        assertThat(availability.listTherapistSlots(DemoCatalogIds.STORE, appClock.today()))
                .isNotEmpty();
    }
}
