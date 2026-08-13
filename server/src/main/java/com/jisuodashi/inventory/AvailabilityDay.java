package com.jisuodashi.inventory;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Store-day snapshot cached for 30s. Idle ⇔ status=FREE and no occupancy;
 * busy if either status≠FREE or an occupancy row exists.
 */
public final class AvailabilityDay {

    private final long storeId;
    private final LocalDate date;
    private final Map<Long, TreeMap<Integer, Cell>> therapists;
    private final Map<Long, TreeMap<Integer, Cell>> beds;
    private final Set<String> occupancy;

    public AvailabilityDay(
            long storeId,
            LocalDate date,
            List<AvailabilityStore.TherapistSlotView> therapistSlots,
            List<AvailabilityStore.BedSlotView> bedSlots,
            List<AvailabilityStore.OccupancyView> occupancies
    ) {
        this.storeId = storeId;
        this.date = date;
        this.therapists = new HashMap<>();
        for (AvailabilityStore.TherapistSlotView row : therapistSlots) {
            therapists.computeIfAbsent(row.therapistId(), id -> new TreeMap<>())
                    .put(row.slotNo(), new Cell(row.slotNo(), row.status(), row.priceOverrideFen()));
        }
        this.beds = new HashMap<>();
        for (AvailabilityStore.BedSlotView row : bedSlots) {
            beds.computeIfAbsent(row.bedId(), id -> new TreeMap<>())
                    .put(row.slotNo(), new Cell(row.slotNo(), row.status(), null));
        }
        this.occupancy = new HashSet<>();
        for (AvailabilityStore.OccupancyView row : occupancies) {
            occupancy.add(occKey(row.resourceType(), row.resourceId(), row.slotNo()));
        }
    }

    public long storeId() {
        return storeId;
    }

    public LocalDate date() {
        return date;
    }

    public Set<Long> therapistIds() {
        return therapists.keySet();
    }

    public TreeMap<Integer, Cell> therapistCells(long therapistId) {
        return therapists.getOrDefault(therapistId, new TreeMap<>());
    }

    public boolean occupied(String resourceType, long resourceId, int slotNo) {
        return occupancy.contains(occKey(resourceType, resourceId, slotNo));
    }

    /** Busy if status is not FREE <em>or</em> occupancy exists. */
    public static boolean busy(String status, boolean occupied) {
        return !SlotStatus.FREE.equals(status) || occupied;
    }

    public boolean therapistIdle(long therapistId, int slotNo) {
        Cell cell = therapistCells(therapistId).get(slotNo);
        if (cell == null) {
            return false;
        }
        return !busy(cell.status(), occupied(ResourceType.THERAPIST, therapistId, slotNo));
    }

    public boolean windowTherapistIdle(long therapistId, int startSlotNo, int n) {
        for (int i = 0; i < n; i++) {
            if (!therapistIdle(therapistId, startSlotNo + i)) {
                return false;
            }
        }
        return true;
    }

    public boolean someBedWindowIdle(int startSlotNo, int n) {
        for (Map.Entry<Long, TreeMap<Integer, Cell>> bed : beds.entrySet()) {
            if (bedWindowIdle(bed.getKey(), bed.getValue(), startSlotNo, n)) {
                return true;
            }
        }
        return false;
    }

    private boolean bedWindowIdle(long bedId, TreeMap<Integer, Cell> cells, int startSlotNo, int n) {
        for (int i = 0; i < n; i++) {
            Cell cell = cells.get(startSlotNo + i);
            if (cell == null || busy(cell.status(), occupied(ResourceType.BED, bedId, startSlotNo + i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Calendar color. Occupancy on a FREE row is treated as LOCKED (not a start).
     * BUFFER stays BUFFER — dashed-gray 不可约, never 已预约 BOOKED (§2.2).
     */
    public static String blockState(String status, boolean occupied) {
        if (SlotStatus.FREE.equals(status) && occupied) {
            return SlotStatus.LOCKED;
        }
        return status == null ? SlotStatus.REST : status;
    }

    static String occKey(String resourceType, long resourceId, int slotNo) {
        return resourceType + "|" + resourceId + "|" + slotNo;
    }

    public record Cell(int slotNo, String status, Long priceOverrideFen) {
    }
}
