package com.jisuodashi;

import com.jisuodashi.common.AppProperties;
import com.jisuodashi.job.JobRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class MuscleMasterApplicationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoadsOnDevProfile() {
        assertThat(appProperties).isNotNull();
        assertThat(appProperties.getJobs().isEnabled()).isFalse();
        assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Shanghai");
        assertThat(applicationContext.getBeanNamesForType(JobRunner.class)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void internalForceReleaseIsOffOnDev() {
        assertThat(appProperties.getInternal().getForceRelease().isEnabled()).isFalse();
        ResponseEntity<Map<String, Object>> response = restTemplate.postForEntity(
                "/internal/force-release?holdId=1",
                null,
                (Class<Map<String, Object>>) (Class<?>) Map.class);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo(40301);
    }

    @Test
    @SuppressWarnings("unchecked")
    void actuatorHealthIsUp() {
        ResponseEntity<Map<String, Object>> response =
                restTemplate.getForEntity("/actuator/health", (Class<Map<String, Object>>) (Class<?>) Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo("UP");
    }
}
