package com.jisuodashi.inventory;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ApiResponse;
import com.jisuodashi.common.ErrorCodes;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping("/api/v1/c")
public class AvailabilityController {

    private final AvailabilityService availability;

    public AvailabilityController(AvailabilityService availability) {
        this.availability = availability;
    }

    @GetMapping("/availability")
    public ApiResponse<AvailabilityDtos.Availability> query(
            @RequestParam long storeId,
            @RequestParam String date,
            @RequestParam long projectId,
            @RequestParam(required = false) Long therapistId,
            @RequestParam(required = false) String includeBusy
    ) {
        return ApiResponse.ok(availability.query(
                storeId, parseDate(date), projectId, therapistId, truthy(includeBusy)));
    }

    /** Design alias: {@code GET /c/stores/{storeId}/availability}. */
    @GetMapping("/stores/{storeId}/availability")
    public ApiResponse<AvailabilityDtos.Availability> queryByStore(
            @PathVariable long storeId,
            @RequestParam String date,
            @RequestParam long projectId,
            @RequestParam(required = false) Long therapistId,
            @RequestParam(required = false) String includeBusy
    ) {
        return ApiResponse.ok(availability.query(
                storeId, parseDate(date), projectId, therapistId, truthy(includeBusy)));
    }

    private static LocalDate parseDate(String raw) {
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "date 须为 YYYY-MM-DD");
        }
    }

    private static boolean truthy(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        return "1".equals(raw) || "true".equalsIgnoreCase(raw) || "yes".equalsIgnoreCase(raw);
    }
}
