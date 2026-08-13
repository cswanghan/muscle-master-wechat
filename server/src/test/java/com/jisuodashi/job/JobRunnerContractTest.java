package com.jisuodashi.job;

import com.jisuodashi.common.ErrorCodes;
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
    void scanIsEvery5MinAsiaShanghai() throws NoSuchMethodException {
        Method method = JobRunner.class.getDeclaredMethod("scanExpiredLocksEvery5Min");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo("0 */5 * * * *");
        assertThat(scheduled.zone()).isEqualTo("Asia/Shanghai");
    }

    @Test
    void generateAndScanJobsAreWired() {
        assertThat(JobRunner.class.getDeclaredFields())
                .filteredOn(f -> !Logger.class.equals(f.getType()) && !f.getName().contains("log"))
                .extracting(java.lang.reflect.Field::getType)
                .contains(SlotGenerateJob.class, SlotScanJob.class);
        assertThat(JobRunner.isJobSuccess(ErrorCodes.OK)).isTrue();
        assertThat(JobRunner.isJobSuccess(ErrorCodes.ILLEGAL_TRANSITION)).isTrue();
        assertThat(JobRunner.isJobSuccess(ErrorCodes.INTERNAL)).isFalse();
    }
}
