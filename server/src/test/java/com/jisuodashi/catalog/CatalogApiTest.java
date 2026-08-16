package com.jisuodashi.catalog;

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
class CatalogApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @Autowired
    private TestRestTemplate rest;

    @Test
    void storesMatchV3DemoAndNeverLeakCipher() {
        ResponseEntity<Map<String, Object>> res = get("/api/v1/c/stores");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("code")).isEqualTo(0);
        List<Map<String, Object>> items = items(body);
        assertThat(items).hasSize(1);
        Map<String, Object> store = items.getFirst();
        assertThat(store.get("storeId")).isEqualTo(String.valueOf(DemoCatalogIds.STORE));
        assertThat(store.get("name")).isEqualTo("肌松大师·演示旗舰店");
        assertThat(store.get("businessStart")).isEqualTo("10:00");
        assertThat(store.get("businessEnd")).isEqualTo("22:00");
        assertThat(store).doesNotContainKeys("phoneCipher", "addressCipher", "phone_cipher");
        assertThat(body.toString()).doesNotContain("phoneCipher");
    }

    @Test
    void storesSortByDistanceWhenLngLatPresent() {
        ResponseEntity<Map<String, Object>> near = get(
                "/api/v1/c/stores?lng=121.4737&lat=31.2304");
        Map<String, Object> item = items(near.getBody()).getFirst();
        assertThat((Integer) item.get("distanceM")).isLessThan(20);
        assertThat(item.get("near")).isEqualTo(true);

        ResponseEntity<Map<String, Object>> far = get("/api/v1/c/stores?lng=0&lat=0");
        Map<String, Object> farItem = items(far.getBody()).getFirst();
        assertThat((Integer) farItem.get("distanceM")).isGreaterThan(1500);
        assertThat(farItem.get("near")).isEqualTo(false);
    }

    @Test
    void storeDetailIncludesListedProjects() {
        ResponseEntity<Map<String, Object>> res = get("/api/v1/c/stores/" + DemoCatalogIds.STORE);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) res.getBody().get("data");
        assertThat(data.get("code")).isEqualTo("DEMO01");
        assertThat(data.get("address")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> projects = (List<Map<String, Object>>) data.get("projects");
        assertThat(projects).hasSize(3);
        assertThat(projects).allSatisfy(p -> {
            assertThat(p.get("durationMinutes")).isNotNull();
            assertThat(p.get("bufferMinutes")).isEqualTo(15);
            assertThat(p.get("priceFen")).isNotNull();
        });
    }

    @Test
    void missingStoreIs40401() {
        ResponseEntity<Map<String, Object>> res = get("/api/v1/c/stores/1");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody().get("code")).isEqualTo(40401);
    }

    @Test
    void therapistsProjectsSymptomsUseV3Ids() {
        List<Map<String, Object>> therapists = items(get("/api/v1/c/therapists").getBody());
        assertThat(therapists).hasSize(DemoFixtures.therapists().size());
        // V3's three keep the lowest ids, so they stay at the front.
        assertThat(therapists).extracting(t -> t.get("name")).startsWith("林晓", "陈默", "周可");
        assertThat(therapists).allSatisfy(t -> assertThat(t).doesNotContainKeys("phoneCipher", "phone"));

        List<Map<String, Object>> projects = items(get("/api/v1/c/projects").getBody());
        assertThat(projects).hasSize(3);
        assertThat(projects).extracting(p -> p.get("priceFen")).contains(19800, 12800, 26800);

        List<Map<String, Object>> symptoms = items(get("/api/v1/c/symptoms").getBody());
        assertThat(symptoms).extracting(s -> s.get("name")).contains("肩颈", "腰骶", "酸胀", "其他");
        assertThat(symptoms).extracting(s -> s.get("type")).contains("BODY_PART", "DISCOMFORT");
    }

    @Test
    void symptomProjectsAndEmptyHint() {
        ResponseEntity<Map<String, Object>> mapped = get("/api/v1/c/symptoms/" + DemoCatalogIds.SYMPTOM_NECK + "/projects");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) mapped.getBody().get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        assertThat(items).hasSize(2);
        assertThat(data.get("hint")).isNull();

        ResponseEntity<Map<String, Object>> empty = get("/api/v1/c/symptoms/" + DemoCatalogIds.SYMPTOM_OTHER + "/projects");
        @SuppressWarnings("unchecked")
        Map<String, Object> emptyData = (Map<String, Object>) empty.getBody().get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> emptyItems = (List<Map<String, Object>>) emptyData.get("items");
        assertThat(emptyItems).isEmpty();
        assertThat(emptyData.get("hint")).isEqualTo("面诊后调整");
    }

    @Test
    void catalogBrowseDoesNotRequireAuth() {
        ResponseEntity<Map<String, Object>> res = get("/api/v1/c/projects?storeId=" + DemoCatalogIds.STORE);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(items(res.getBody())).isNotEmpty();
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
