package com.jisuodashi.order;

/** Side effects run after CAS. Release* must not call fire (Law A / D25). */
public enum OrderSide {
    NONE,
    CONFIRM_PAID,
    RELEASE_LOCK,
    RELEASE_UNCONSUMED_START,
    RELEASE_UNCONSUMED_NOW,
    RELEASE_ADDON,
    CHECKED_IN_AT,
    SERVICE_RECORD,
    ENDED_AT,
    NO_SHOW_COUNT,
    REFUND,
    RESCHEDULE,
    SWAP_THERAPIST
}
