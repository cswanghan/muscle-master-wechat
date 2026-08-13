package com.jisuodashi.order;

import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;

import java.time.Instant;

/** START_SERVICE / COMPLETE_SERVICE sides. Must run in the same TX as CAS. */
public interface ServiceRecordSide {

    void insertStarted(BookingOrderRef order, Instant now);

    void markEnded(long orderId, Instant now);
}
