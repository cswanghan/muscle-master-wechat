package com.jisuodashi.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Single runner (D16). Gated by {@code app.jobs.enabled}. This PR only fires generate.
 * Startup run backfills today..today+15 when the window is empty.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.jobs", name = "enabled", havingValue = "true")
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final SlotGenerateJob slotGenerateJob;

    public JobRunner(SlotGenerateJob slotGenerateJob) {
        this.slotGenerateJob = slotGenerateJob;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("JobRunner starting SlotGenerateJob (first-run backfill if window empty)");
        slotGenerateJob.run();
    }

    @Scheduled(cron = "0 15 2 * * *", zone = "Asia/Shanghai")
    public void dailyGenerateAt0215Shanghai() {
        slotGenerateJob.run();
    }
}
