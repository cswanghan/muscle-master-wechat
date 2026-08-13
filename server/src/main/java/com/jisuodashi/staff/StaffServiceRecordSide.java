package com.jisuodashi.staff;

import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import com.jisuodashi.order.ServiceRecordSide;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class StaffServiceRecordSide implements ServiceRecordSide {

    private final TreatmentNoteRepository notes;
    private final SnowflakeIdGenerator ids;

    public StaffServiceRecordSide(TreatmentNoteRepository notes, SnowflakeIdGenerator ids) {
        this.notes = notes;
        this.ids = ids;
    }

    @Override
    public void insertStarted(BookingOrderRef order, Instant now) {
        notes.ensureServiceRecord(
                ids.nextId(), order.id(), order.therapistId(), order.customerId(), order.storeId(), now);
    }

    @Override
    public void markEnded(long orderId, Instant now) {
        notes.markLatestEnded(orderId, now);
    }

    /** Close the open segment and start a new one for the current order therapist. */
    public void swapSegment(BookingOrderRef order, Instant now) {
        notes.markLatestEnded(order.id(), now);
        notes.insertServiceRecord(
                ids.nextId(), order.id(), order.therapistId(), order.customerId(), order.storeId(), now);
    }
}
