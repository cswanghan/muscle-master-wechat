package com.jisuodashi.inventory;

/** Shared by therapist_slot and bed_slot. Generation writes FREE or REST only. */
public final class SlotStatus {

    public static final String FREE = "FREE";
    public static final String REST = "REST";
    public static final String LOCKED = "LOCKED";
    public static final String BOOKED = "BOOKED";
    public static final String BUFFER = "BUFFER";

    private SlotStatus() {
    }
}
