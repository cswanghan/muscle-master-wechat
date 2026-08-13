package com.jisuodashi.catalog;

import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.PhoneCrypto;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogServiceCacheTest {

    @Test
    void openAndDistanceAreComputedPerRequest() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-14T04:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return Clock.fixed(now.get(), zone);
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        AppProperties props = new AppProperties();
        props.getCatalog().setStoreCacheTtl(Duration.ofMinutes(5));
        props.getCrypto().setPhonePepper("dev-phone-pepper");
        CatalogService catalog = new CatalogService(
                new InMemoryCatalogRepository(), new PhoneCrypto(props), clock, props);

        CatalogDtos.StoreListItem noon = catalog.listStores(null, null, null, 20).items().getFirst();
        assertThat(noon.open()).isTrue();

        now.set(Instant.parse("2026-08-14T15:00:00Z"));
        CatalogDtos.StoreListItem night = catalog.listStores(null, null, null, 20).items().getFirst();
        assertThat(night.open()).isFalse();

        CatalogDtos.StoreListItem near = catalog.listStores(121.4737, 31.2304, null, 20).items().getFirst();
        CatalogDtos.StoreListItem far = catalog.listStores(0d, 0d, null, 20).items().getFirst();
        assertThat(near.near()).isTrue();
        assertThat(far.near()).isFalse();
        assertThat(far.distanceM()).isNotEqualTo(near.distanceM());
    }
}
