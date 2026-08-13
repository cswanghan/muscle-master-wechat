package com.jisuodashi.staff;

import com.jisuodashi.auth.DemoStaffIds;
import com.jisuodashi.rbac.RbacDemoIds;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
@Profile("dev")
public class InMemoryTreatmentNoteRepository implements TreatmentNoteRepository {

    private static final TreatmentNote SEED = new TreatmentNote(
            RbacDemoIds.NOTE_ID,
            RbacDemoIds.NOTE_ORDER,
            RbacDemoIds.STORE,
            RbacDemoIds.THERAPIST_LIN,
            DemoStaffIds.T1,
            "肩颈放松，客户反馈酸胀减轻",
            Instant.parse("2026-08-14T04:00:00Z"));

    private final CopyOnWriteArrayList<TreatmentNote> notes = new CopyOnWriteArrayList<>(List.of(SEED));
    private final ConcurrentHashMap<Long, ServiceRecord> records = new ConcurrentHashMap<>();

    @Override
    public List<TreatmentNote> findByOrderId(long orderId) {
        return notes.stream()
                .filter(n -> n.orderId() == orderId)
                .sorted(Comparator.comparingLong(TreatmentNote::id))
                .toList();
    }

    @Override
    public TreatmentNote insert(TreatmentNote note) {
        notes.add(note);
        return note;
    }

    @Override
    public Optional<ServiceRecord> findLatestServiceRecord(long orderId) {
        return records.values().stream()
                .filter(r -> r.orderId() == orderId)
                .max(Comparator.comparingLong(ServiceRecord::id));
    }

    @Override
    public ServiceRecord ensureServiceRecord(
            long id, long orderId, long therapistId, long customerId, long storeId, Instant now) {
        Optional<ServiceRecord> existing = findLatestServiceRecord(orderId);
        if (existing.isPresent()) {
            return existing.get();
        }
        ServiceRecord created = new ServiceRecord(id, orderId, therapistId, customerId, storeId, now, null, now);
        records.put(id, created);
        return created;
    }

    @Override
    public void markLatestEnded(long orderId, Instant endedAt) {
        findLatestServiceRecord(orderId).ifPresent(row -> records.put(
                row.id(),
                new ServiceRecord(
                        row.id(), row.orderId(), row.therapistId(), row.customerId(),
                        row.storeId(), row.startedAt(), endedAt, row.createdAt())));
    }

    public void clearAdded() {
        notes.removeIf(n -> n.id() != RbacDemoIds.NOTE_ID);
        records.clear();
    }

    @Override
    public ServiceRecord insertServiceRecord(
            long id, long orderId, long therapistId, long customerId, long storeId, Instant now) {
        ServiceRecord created = new ServiceRecord(id, orderId, therapistId, customerId, storeId, now, null, now);
        records.put(id, created);
        return created;
    }

    @Override
    public List<ServiceRecord> listServiceRecords(long orderId) {
        return records.values().stream()
                .filter(r -> r.orderId() == orderId)
                .sorted(Comparator.comparingLong(ServiceRecord::id))
                .toList();
    }

    @Override
    public void insertSystemNote(long id, long orderId, long authorStaffId, String content, Instant now) {
        long therapistId = findLatestServiceRecord(orderId).map(ServiceRecord::therapistId).orElse(0L);
        long storeId = findLatestServiceRecord(orderId).map(ServiceRecord::storeId).orElse(0L);
        notes.add(new TreatmentNote(id, orderId, storeId, therapistId, authorStaffId, content, now));
    }
}
