package com.jisuodashi.inventory;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static com.jisuodashi.inventory.OccupyFixtures.T1;
import static com.jisuodashi.inventory.OccupyFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;

class TherapistDayLockTest {

    @Test
    void releaseRequiresMatchingToken() {
        AtomicLong now = new AtomicLong(1_000_000);
        InMemoryTherapistDayLock lock = new InMemoryTherapistDayLock(now::get);
        String first = lock.tryAcquire(T1, TODAY);
        assertThat(first).isNotBlank();
        assertThat(lock.tryAcquire(T1, TODAY)).isNull();

        lock.release(T1, TODAY, "not-the-token");
        assertThat(lock.tryAcquire(T1, TODAY)).isNull();

        lock.release(T1, TODAY, first);
        String second = lock.tryAcquire(T1, TODAY);
        assertThat(second).isNotBlank().isNotEqualTo(first);
    }

    @Test
    void expiredLeaseCanBeTakenWithoutStealingViaWrongRelease() {
        AtomicLong now = new AtomicLong(1_000_000);
        InMemoryTherapistDayLock lock = new InMemoryTherapistDayLock(now::get);
        String first = lock.tryAcquire(T1, TODAY);
        now.addAndGet(TherapistDayLock.TTL_SECONDS * 1000L + 1);
        String second = lock.tryAcquire(T1, TODAY);
        assertThat(second).isNotBlank().isNotEqualTo(first);
        lock.release(T1, TODAY, first);
        assertThat(lock.tryAcquire(T1, TODAY)).isNull();
    }
}
