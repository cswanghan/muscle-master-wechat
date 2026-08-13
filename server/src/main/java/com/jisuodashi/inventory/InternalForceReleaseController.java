package com.jisuodashi.inventory;

import com.jisuodashi.common.ApiResponse;
import com.jisuodashi.job.ForceReleaseJob;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Internal rollback drill. Not under /api/v1/c|t|f|a. */
@RestController
@RequestMapping("/internal")
public class InternalForceReleaseController {

    private final ForceReleaseJob forceReleaseJob;

    public InternalForceReleaseController(ForceReleaseJob forceReleaseJob) {
        this.forceReleaseJob = forceReleaseJob;
    }

    @PostMapping("/force-release")
    public ApiResponse<ReleaseResult> forceRelease(@RequestParam("holdId") long holdId) {
        return ApiResponse.ok(forceReleaseJob.run(holdId));
    }
}
