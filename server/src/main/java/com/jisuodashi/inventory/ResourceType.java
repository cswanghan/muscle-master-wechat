package com.jisuodashi.inventory;

/**
 * Occupancy resource_type.
 * lockNew locks therapist then beds on purpose: therapist-day Redis already serializes
 * the same therapist; beds are walked by sort_no, id. Mixed-resource writers
 * (reschedule) use global order (resource_type, resource_id, slot_date, slot_no).
 */
public final class ResourceType {

    public static final String THERAPIST = "THERAPIST";
    public static final String BED = "BED";

    private ResourceType() {
    }
}
