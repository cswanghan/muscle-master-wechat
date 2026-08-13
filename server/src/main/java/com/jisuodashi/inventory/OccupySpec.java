package com.jisuodashi.inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * P0 occupy window: {@code N = ceil((duration+buffer)/15)}, {@code B = 1}.
 * Last slot is BUFFER; the rest are LOCKED until pay.
 */
public record OccupySpec(int slotCount, int bufferSlots, int durationMinutes, int bufferMinutes) {

    public static final int P0_BUFFER_SLOTS = 1;
    public static final int SLOT_MINUTES = 15;

    public static OccupySpec of(int durationMinutes, int bufferMinutes) {
        if (durationMinutes <= 0) {
            throw new IllegalArgumentException("durationMinutes must be positive");
        }
        if (bufferMinutes <= 0) {
            throw new IllegalArgumentException("bufferMinutes must be positive");
        }
        int n = (durationMinutes + bufferMinutes + SLOT_MINUTES - 1) / SLOT_MINUTES;
        return new OccupySpec(n, P0_BUFFER_SLOTS, durationMinutes, bufferMinutes);
    }

    public List<Integer> slotNos(int startSlotNo) {
        List<Integer> slots = new ArrayList<>(slotCount);
        for (int i = 0; i < slotCount; i++) {
            slots.add(startSlotNo + i);
        }
        return List.copyOf(slots);
    }

    public int endSlotNo(int startSlotNo) {
        return startSlotNo + slotCount;
    }

    public int bufferFrom(int startSlotNo) {
        return startSlotNo + slotCount - bufferSlots;
    }

    public boolean isBuffer(int startSlotNo, int slotNo) {
        return slotNo >= bufferFrom(startSlotNo);
    }

    public String destStatus(int startSlotNo, int slotNo) {
        return isBuffer(startSlotNo, slotNo) ? SlotStatus.BUFFER : SlotStatus.LOCKED;
    }
}
