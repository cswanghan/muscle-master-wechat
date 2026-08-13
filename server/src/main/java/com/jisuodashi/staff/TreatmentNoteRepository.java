package com.jisuodashi.staff;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TreatmentNoteRepository {

    List<TreatmentNote> findByOrderId(long orderId);

    TreatmentNote insert(TreatmentNote note);

    Optional<ServiceRecord> findLatestServiceRecord(long orderId);

    ServiceRecord ensureServiceRecord(
            long id, long orderId, long therapistId, long customerId, long storeId, Instant now);

    void markLatestEnded(long orderId, Instant endedAt);
}
