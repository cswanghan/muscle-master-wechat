package com.jisuodashi.catalog;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.GrayStores;
import com.jisuodashi.inventory.AvailabilityCache;
import com.jisuodashi.inventory.AvailabilityDtos;
import com.jisuodashi.inventory.AvailabilityService;
import com.jisuodashi.inventory.InMemoryAvailabilityStore;
import com.jisuodashi.inventory.SlotStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class GrayApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final long VISITOR = 3_100_000_000_000_000_499L;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void cStoreListHidesNonGray() {
        ResponseEntity<Map<String, Object>> res = get("/api/v1/c/stores");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> items = items(res.getBody());
        assertThat(items).extracting(i -> i.get("storeId"))
                .containsExactly(String.valueOf(DemoCatalogIds.STORE))
                .doesNotContain(String.valueOf(DemoCatalogIds.STORE_EAST));
    }

    @Test
    void getNonGrayStoreIs40401() {
        ResponseEntity<Map<String, Object>> res = get("/api/v1/c/stores/" + DemoCatalogIds.STORE_EAST);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(40401);
    }

    @Test
    void availabilityOnNonGrayStoreIs40401() {
        ResponseEntity<Map<String, Object>> res = get(
                "/api/v1/c/stores/" + DemoCatalogIds.STORE_EAST
                        + "/availability?date=2026-08-14&projectId=" + DemoCatalogIds.PROJECT_P60);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("code")).isEqualTo(40401);
    }

    @Test
    void visitingTherapistSlotsAtGrayStoreStayVisible() {
        InMemoryCatalogRepository catalog = new InMemoryCatalogRepository();
        catalog.putTherapist(new CatalogModels.Therapist(
                VISITOR, VISITOR, "T099", "支援技师",
                DemoCatalogIds.STORE_EAST, "MIDDLE", null, "跨店支援", 450, 1,
                List.of(DemoCatalogIds.PROJECT_P60), List.of()));
        InMemoryAvailabilityStore avail = InMemoryAvailabilityStore.blank();
        LocalDate day = LocalDate.of(2026, 8, 14);
        avail.seedTherapistSlots(VISITOR, day, 40, 44, SlotStatus.FREE);
        avail.seedBedSlots(3_100_000_000_000_000_201L, day, 40, 44, SlotStatus.FREE);
        AppProperties props = new AppProperties();
        GrayStores gray = new GrayStores(props);
        AvailabilityService svc = new AvailabilityService(
                avail, catalog, new AvailabilityCache(Duration.ofSeconds(30), Clock.systemUTC()));
        svc.setGrayStores(gray);

        AvailabilityDtos.Availability body = svc.query(
                DemoCatalogIds.STORE, day, DemoCatalogIds.PROJECT_P60, null, true);
        assertThat(body.therapists()).extracting(AvailabilityDtos.Therapist::therapistId)
                .contains(String.valueOf(VISITOR));

        assertThatThrownBy(() -> svc.query(
                DemoCatalogIds.STORE_EAST, day, DemoCatalogIds.PROJECT_P60, null, true))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.NOT_FOUND);
    }

    private ResponseEntity<Map<String, Object>> get(String path) {
        return rest.exchange(path, HttpMethod.GET, null, MAP);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> body) {
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        return (List<Map<String, Object>>) data.get("items");
    }
}
