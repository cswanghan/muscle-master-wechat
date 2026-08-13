package com.jisuodashi.inventory;

import com.jisuodashi.common.AppClock;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 15-minute slot addressing. {@code slot_no = hour*4 + minute/15} (D10).
 * Ranges are half-open {@code [start, end)}.
 */
public final class SlotTimes {

    public static final ZoneId ZONE = AppClock.SHANGHAI;
    public static final int SLOTS_PER_DAY = 24 * 4;

    private SlotTimes() {
    }

    public static int toSlotNo(LocalTime t) {
        if (t == null) {
            throw new IllegalArgumentException("time is required");
        }
        if (t.getMinute() % 15 != 0 || t.getSecond() != 0 || t.getNano() != 0) {
            throw new IllegalArgumentException("time must align to 15 minutes: " + t);
        }
        return t.getHour() * 4 + t.getMinute() / 15;
    }

    public static LocalTime toTime(int slotNo) {
        if (slotNo < 0 || slotNo >= SLOTS_PER_DAY) {
            throw new IllegalArgumentException("slot_no out of day: " + slotNo);
        }
        return LocalTime.of(slotNo / 4, (slotNo % 4) * 15);
    }

    /** Half-open {@code [start, end)} slot numbers. */
    public static List<Integer> range(LocalTime start, LocalTime end) {
        int from = toSlotNo(start);
        int to = toSlotNo(end);
        if (to <= from) {
            throw new IllegalArgumentException("end must be after start on the same day: " + start + "–" + end);
        }
        List<Integer> slots = new ArrayList<>(to - from);
        for (int i = from; i < to; i++) {
            slots.add(i);
        }
        return List.copyOf(slots);
    }
}
