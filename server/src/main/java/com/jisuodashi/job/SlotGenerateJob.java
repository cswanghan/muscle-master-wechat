package com.jisuodashi.job;

import com.jisuodashi.inventory.SlotGenerateResult;
import com.jisuodashi.inventory.SlotGenerateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** Daily calendar generation. Occupancy / lockNew are not this job. */
@Component
public class SlotGenerateJob {

    private static final Logger log = LoggerFactory.getLogger(SlotGenerateJob.class);

    private final SlotGenerateService service;

    public SlotGenerateJob(SlotGenerateService service) {
        this.service = service;
    }

    public SlotGenerateResult run() {
        return logResult(service.generate());
    }

    public SlotGenerateResult run(LocalDate today) {
        return logResult(service.generate(today));
    }

    private static SlotGenerateResult logResult(SlotGenerateResult result) {
        log.info(
                "SlotGenerateJob firstRun={} window={}..{} therapist +{}/skip {} bed +{}/skip {} REST={} FREE={} conflicts={}",
                result.firstRun(), result.from(), result.to(),
                result.therapistInserted(), result.therapistIgnored(),
                result.bedInserted(), result.bedIgnored(),
                result.restWritten(), result.freeWritten(), result.conflicts());
        return result;
    }
}
