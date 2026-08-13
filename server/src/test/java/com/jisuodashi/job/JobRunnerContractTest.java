package com.jisuodashi.job;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class JobRunnerContractTest {

    @Test
    void singleRunnerGatedByAppJobsEnabled() {
        ConditionalOnProperty gate = JobRunner.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(gate).isNotNull();
        assertThat(gate.prefix()).isEqualTo("app.jobs");
        assertThat(gate.name()).containsExactly("enabled");
        assertThat(gate.havingValue()).isEqualTo("true");
    }

    @Test
    void dailyGenerateIs0215AsiaShanghai() throws NoSuchMethodException {
        Method method = JobRunner.class.getDeclaredMethod("dailyGenerateAt0215Shanghai");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo("0 15 2 * * *");
        assertThat(scheduled.zone()).isEqualTo("Asia/Shanghai");
    }

    @Test
    void onlyGenerateJobIsWired() throws NoSuchMethodException {
        assertThat(JobRunner.class.getDeclaredFields())
                .filteredOn(f -> !Logger.class.equals(f.getType()))
                .extracting(java.lang.reflect.Field::getType)
                .containsExactly(SlotGenerateJob.class);
        assertThat(SlotGenerateJob.class.getDeclaredMethod("run")).isNotNull();
    }
}
