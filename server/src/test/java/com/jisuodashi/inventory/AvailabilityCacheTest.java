package com.jisuodashi.inventory;

import com.jisuodashi.catalog.DemoCatalogIds;
import com.jisuodashi.catalog.InMemoryCatalogRepository;
import com.jisuodashi.common.AppClock;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AvailabilityCacheTest {

    private static final LocalDate DAY = InMemoryAvailabilityStore.DEMO_DATE;
    private static final long STORE = DemoCatalogIds.STORE;

    @Test
    void storeDateTtlIs30sUntilInvalidate() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-14T02:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return Clock.fixed(now.get(), zone);
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        AvailabilityCache cache = new AvailabilityCache(Duration.ofSeconds(30), clock);
        AtomicInteger loads = new AtomicInteger();
        AvailabilityDay first = cache.get(STORE, DAY, () -> {
            loads.incrementAndGet();
            return emptyDay();
        });
        AvailabilityDay hit = cache.get(STORE, DAY, () -> {
            loads.incrementAndGet();
            return emptyDay();
        });
        assertThat(hit).isSameAs(first);
        assertThat(loads.get()).isEqualTo(1);
        assertThat(cache.hits()).isEqualTo(1);
        assertThat(cache.misses()).isEqualTo(1);
        assertThat(cache.contains(STORE, DAY)).isTrue();

        now.set(now.get().plusSeconds(29));
        cache.get(STORE, DAY, () -> {
            loads.incrementAndGet();
            return emptyDay();
        });
        assertThat(loads.get()).isEqualTo(1);

        now.set(now.get().plusSeconds(2));
        cache.get(STORE, DAY, () -> {
            loads.incrementAndGet();
            return emptyDay();
        });
        assertThat(loads.get()).isEqualTo(2);

        cache.invalidate(STORE, DAY);
        assertThat(cache.contains(STORE, DAY)).isFalse();
        cache.get(STORE, DAY, () -> {
            loads.incrementAndGet();
            return emptyDay();
        });
        assertThat(loads.get()).isEqualTo(3);
    }

    @Test
    void lockNewAndReleaseInvalidateStoreDate() {
        AvailabilityCache cache = new AvailabilityCache(Duration.ofSeconds(30), Clock.systemUTC());
        cache.get(STORE, DAY, AvailabilityCacheTest::emptyDay);
        assertThat(cache.contains(STORE, DAY)).isTrue();

        InMemorySlotOccupyStore occupy = OccupyFixtures.demoStore();
        SlotOccupyService occupySvc = new SlotOccupyService(
                occupy,
                new InMemoryTherapistDayLock(),
                new java.util.concurrent.atomic.AtomicLong(9_200_000_000_000_000_000L)::incrementAndGet,
                new AppClock(Clock.fixed(
                        DAY.atTime(19, 0).atZone(AppClock.SHANGHAI).toInstant(), AppClock.SHANGHAI)),
                cache);
        occupySvc.lockNew(OccupyFixtures.cmd("avail-lock", OccupyFixtures.T1, OccupyFixtures.START_1930));
        assertThat(cache.contains(STORE, DAY)).isFalse();

        cache.get(STORE, DAY, AvailabilityCacheTest::emptyDay);
        assertThat(cache.contains(STORE, DAY)).isTrue();
        occupySvc.onRelease(STORE, DAY);
        assertThat(cache.contains(STORE, DAY)).isFalse();
    }

    @Test
    void mutationWithoutInvalidateServesStaleThenFreshAfterHook() {
        InMemoryAvailabilityStore store = InMemoryAvailabilityStore.blank();
        store.seedTherapistSlots(DemoCatalogIds.THERAPIST_LIN, DAY, 40, 88, SlotStatus.FREE);
        store.seedBedSlots(InMemoryAvailabilityStore.BED1, DAY, 40, 88, SlotStatus.FREE);
        AvailabilityCache cache = new AvailabilityCache(Duration.ofSeconds(30), Clock.systemUTC());
        AvailabilityService svc = new AvailabilityService(store, new InMemoryCatalogRepository(), cache);

        List<Integer> before = starts(svc);
        assertThat(before).contains(78);

        store.setTherapistStatus(DemoCatalogIds.THERAPIST_LIN, DAY, 78, SlotStatus.LOCKED);
        store.seedOccupancy(ResourceType.THERAPIST, DemoCatalogIds.THERAPIST_LIN, DAY, 78, 83);
        List<Integer> stale = starts(svc);
        assertThat(stale).contains(78);

        svc.invalidate(STORE, DAY);
        List<Integer> fresh = starts(svc);
        assertThat(fresh).doesNotContain(78);
    }

    @Test
    void invalidateBumpsGenerationAndDropsInFlightPut() throws Exception {
        AvailabilityCache cache = new AvailabilityCache(Duration.ofSeconds(30), Clock.systemUTC());
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AvailabilityDay stale = emptyDay();
        Thread inflight = new Thread(() -> cache.get(STORE, DAY, () -> {
            loading.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("release timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return stale;
        }));
        inflight.start();
        assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
        long before = cache.generation(STORE, DAY);
        cache.invalidate(STORE, DAY);
        assertThat(cache.generation(STORE, DAY)).isGreaterThan(before);
        release.countDown();
        inflight.join(5_000);
        assertThat(cache.contains(STORE, DAY)).isFalse();

        AvailabilityDay next = emptyDay();
        AvailabilityDay got = cache.get(STORE, DAY, () -> next);
        assertThat(got).isSameAs(next);
        assertThat(cache.contains(STORE, DAY)).isTrue();
    }

    private static List<Integer> starts(AvailabilityService svc) {
        return svc.query(STORE, DAY, DemoCatalogIds.PROJECT_P60, DemoCatalogIds.THERAPIST_LIN, false)
                .therapists().getFirst().starts().stream()
                .map(AvailabilityDtos.Start::slotNo)
                .toList();
    }

    private static AvailabilityDay emptyDay() {
        return new AvailabilityDay(STORE, DAY, List.of(), List.of(), List.of());
    }
}
