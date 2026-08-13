package com.jisuodashi.frontdesk;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.inventory.SlotOccupyStore;
import com.jisuodashi.inventory.SlotOccupyStore.SlotRow;
import com.jisuodashi.inventory.SlotStatus;
import com.jisuodashi.rbac.StoreScope;
import com.jisuodashi.rbac.StoreScopeContext;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

@Service
public class UtilizationService {

    private final SlotOccupyStore store;
    private final AppClock clock;

    public UtilizationService(SlotOccupyStore store, AppClock clock) {
        this.store = store;
        this.clock = clock;
    }

    public FrontDeskDtos.UtilizationResponse utilization(String dateRaw, Long storeId) {
        StoreScope scope = StoreScopeContext.require();
        long sid = resolveStore(scope, storeId);
        LocalDate date = parseDate(dateRaw);
        return compute(sid, date, store.listTherapistSlotsByStore(sid, date));
    }

    public static FrontDeskDtos.UtilizationResponse compute(long storeId, LocalDate date, List<SlotRow> slots) {
        int occupied = 0;
        int denom = 0;
        TreeMap<Integer, int[]> byHour = new TreeMap<>();
        if (slots != null) {
            for (SlotRow row : slots) {
                if (row == null || row.status() == null) {
                    continue;
                }
                int hour = row.slotNo() / 4;
                int[] bucket = byHour.computeIfAbsent(hour, h -> new int[2]);
                if (SlotStatus.REST.equals(row.status())) {
                    continue;
                }
                denom++;
                bucket[1]++;
                if (occupiedStatus(row.status())) {
                    occupied++;
                    bucket[0]++;
                }
            }
        }
        List<FrontDeskDtos.HourUtilization> hours = new ArrayList<>();
        for (var e : byHour.entrySet()) {
            hours.add(new FrontDeskDtos.HourUtilization(e.getKey(), rate(e.getValue()[0], e.getValue()[1])));
        }
        return new FrontDeskDtos.UtilizationResponse(
                String.valueOf(storeId),
                date.toString(),
                rate(occupied, denom),
                List.copyOf(hours));
    }

    private long resolveStore(StoreScope scope, Long requested) {
        if (requested != null) {
            scope.assertContains(requested);
            return requested;
        }
        if (!scope.storeIds().isEmpty()) {
            return scope.storeIds().getFirst();
        }
        if (scope.all()) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "storeId 不能为空");
        }
        throw new ApiException(ErrorCodes.DATA_SCOPE, "数据域拒绝");
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank() || "今天".equals(raw) || "today".equalsIgnoreCase(raw)) {
            return clock.today();
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "date 须为 YYYY-MM-DD");
        }
    }

    private static boolean occupiedStatus(String status) {
        return SlotStatus.BOOKED.equals(status)
                || SlotStatus.BUFFER.equals(status)
                || SlotStatus.LOCKED.equals(status);
    }

    private static Integer rate(int occupied, int denom) {
        if (denom <= 0) {
            return null;
        }
        return occupied * 10_000 / denom;
    }
}
