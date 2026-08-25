package com.jisuodashi.order;

import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;

import java.time.Instant;

/** {@code REVIEW} side. Must run in the same TX as CAS, like {@link ServiceRecordSide}. */
public interface ReviewSide {

    void insertReview(BookingOrderRef order, ReviewDraft draft, Instant now);
}
