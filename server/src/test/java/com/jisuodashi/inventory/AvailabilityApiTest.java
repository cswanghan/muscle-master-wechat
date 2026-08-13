package com.jisuodashi.inventory;

import com.jisuodashi.catalog.DemoCatalogIds;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class AvailabilityApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AvailabilityCache cache;

    @Test
    void queryStartsAreBookableOnlyAndCacheHitsStoreDate() {
        cache.invalidate(DemoCatalogIds.STORE, InMemoryAvailabilityStore.DEMO_DATE);
        long misses = cache.misses();

        ResponseEntity<Map<String, Object>> res = get(
                "/api/v1/c/availability?storeId=" + DemoCatalogIds.STORE
                        + "&date=2026-08-14&projectId=" + DemoCatalogIds.PROJECT_P60
                        + "&therapistId=" + DemoCatalogIds.THERAPIST_LIN);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = data(res);
        assertThat(data.get("storeId")).isEqualTo(String.valueOf(DemoCatalogIds.STORE));
        assertThat(data.get("date")).isEqualTo("2026-08-14");
        assertThat(data.get("occupySlots")).isEqualTo(5);
        assertThat(data.get("slotMinutes")).isEqualTo(15);
        List<Map<String, Object>> therapists = therapists(data);
        assertThat(therapists).hasSize(1);
        List<Map<String, Object>> starts = starts(therapists.getFirst());
        assertThat(starts).isNotEmpty();
        assertThat(starts).allSatisfy(s -> {
            assertThat(s.get("slotNo")).isNotNull();
            assertThat(s.get("start")).isNotNull();
            assertThat(s.get("priceFen")).isEqualTo(19800);
            assertThat(s).doesNotContainKey("state");
        });
        List<Integer> slotNos = starts.stream().map(s -> (Integer) s.get("slotNo")).toList();
        assertThat(slotNos).contains(40, 83).doesNotContain(56, 78);
        assertThat(therapists.getFirst()).doesNotContainKey("blocks");
        assertThat(cache.misses()).isGreaterThan(misses);

        long hits = cache.hits();
        get("/api/v1/c/availability?storeId=" + DemoCatalogIds.STORE
                + "&date=2026-08-14&projectId=" + DemoCatalogIds.PROJECT_P45);
        assertThat(cache.hits()).isGreaterThan(hits);
    }

    @Test
    void includeBusyShowsFourStatesAndLockedIsNotAStart() {
        ResponseEntity<Map<String, Object>> res = get(
                "/api/v1/c/availability?storeId=" + DemoCatalogIds.STORE
                        + "&date=2026-08-14&projectId=" + DemoCatalogIds.PROJECT_P60
                        + "&includeBusy=1");
        Map<String, Object> data = data(res);
        List<Map<String, Object>> therapists = therapists(data);
        assertThat(therapists).hasSize(3);

        Map<String, Object> lin = therapists.stream()
                .filter(t -> DemoCatalogIds.THERAPIST_LIN == Long.parseLong((String) t.get("therapistId")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) lin.get("blocks");
        assertThat(blocks).extracting(b -> b.get("state"))
                .contains(SlotStatus.FREE, SlotStatus.REST, SlotStatus.LOCKED);
        assertThat(starts(lin).stream().map(s -> s.get("slotNo"))).doesNotContain(78, 56);

        Map<String, Object> zhou = therapists.stream()
                .filter(t -> DemoCatalogIds.THERAPIST_ZHOU == Long.parseLong((String) t.get("therapistId")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> zhouBlocks = (List<Map<String, Object>>) zhou.get("blocks");
        assertThat(zhouBlocks).extracting(b -> b.get("state")).contains(SlotStatus.BOOKED);
        assertThat(starts(zhou).stream().map(s -> s.get("slotNo"))).doesNotContain(40);
    }

    @Test
    void designAliasPathWorks() {
        ResponseEntity<Map<String, Object>> res = get(
                "/api/v1/c/stores/" + DemoCatalogIds.STORE
                        + "/availability?date=2026-08-14&projectId=" + DemoCatalogIds.PROJECT_P60);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(res).get("projectId")).isEqualTo(String.valueOf(DemoCatalogIds.PROJECT_P60));
    }

    @Test
    void missingParamsAndUnknownStore() {
        ResponseEntity<Map<String, Object>> missing = get("/api/v1/c/availability?storeId=1&date=2026-08-14");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getBody().get("code")).isEqualTo(40001);

        ResponseEntity<Map<String, Object>> badDate = get(
                "/api/v1/c/availability?storeId=" + DemoCatalogIds.STORE
                        + "&date=not-a-date&projectId=" + DemoCatalogIds.PROJECT_P60);
        assertThat(badDate.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map<String, Object>> missingStore = get(
                "/api/v1/c/availability?storeId=1&date=2026-08-14&projectId=" + DemoCatalogIds.PROJECT_P60);
        assertThat(missingStore.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missingStore.getBody().get("code")).isEqualTo(40401);
    }

    private ResponseEntity<Map<String, Object>> get(String path) {
        return rest.exchange(path, HttpMethod.GET, null, MAP);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> res) {
        return (Map<String, Object>) res.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> therapists(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("therapists");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> starts(Map<String, Object> therapist) {
        return (List<Map<String, Object>>) therapist.get("starts");
    }
}
