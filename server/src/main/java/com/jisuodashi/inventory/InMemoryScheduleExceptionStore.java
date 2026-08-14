package com.jisuodashi.inventory;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** dev-profile fake. Undo log mirrors {@link InMemorySlotOccupyStore} so rollback is honest. */
@Repository
@Profile("dev")
public class InMemoryScheduleExceptionStore implements ScheduleExceptionStore {

    private final Map<Long, ScheduleExceptionRow> rows = new ConcurrentHashMap<>();
    private final ThreadLocal<Work> work = new ThreadLocal<>();

    @Override
    public void beginWork() {
        Work w = work.get();
        if (w != null) {
            w.depth++;
            return;
        }
        work.set(new Work());
    }

    @Override
    public void commitWork() {
        Work w = work.get();
        if (w == null) {
            return;
        }
        if (w.depth > 0) {
            w.depth--;
            return;
        }
        work.remove();
    }

    @Override
    public void rollbackWork() {
        Work w = work.get();
        if (w == null) {
            return;
        }
        if (w.depth > 0) {
            w.depth--;
            return;
        }
        for (int i = w.undos.size() - 1; i >= 0; i--) {
            w.undos.get(i).run();
        }
        work.remove();
    }

    @Override
    public void insert(ScheduleExceptionRow row) {
        Work w = requireWork();
        if (rows.putIfAbsent(row.id(), row) != null) {
            throw new IllegalStateException("duplicate schedule_exception " + row.id());
        }
        w.undos.add(() -> rows.remove(row.id()));
    }

    @Override
    public ScheduleExceptionRow findById(long id) {
        return rows.get(id);
    }

    @Override
    public ScheduleExceptionRow lockById(long id) {
        requireWork();
        return rows.get(id);
    }

    @Override
    public int casStatus(long id, String expectedStatus, String nextStatus, LocalDateTime now) {
        Work w = requireWork();
        ScheduleExceptionRow prev = rows.get(id);
        if (prev == null || !prev.status().equals(expectedStatus)) {
            return 0;
        }
        rows.put(id, prev.withStatus(nextStatus, now));
        w.undos.add(() -> rows.put(id, prev));
        return 1;
    }

    @Override
    public List<ScheduleExceptionRow> list(
            List<Long> storeIds, LocalDate from, LocalDate to, String status) {
        return rows.values().stream()
                .filter(r -> storeIds == null || storeIds.isEmpty()
                        || (r.storeId() != null && storeIds.contains(r.storeId())))
                .filter(r -> from == null || !r.exceptDate().isBefore(from))
                .filter(r -> to == null || !r.exceptDate().isAfter(to))
                .filter(r -> status == null || status.equals(r.status()))
                .sorted(Comparator.comparingLong(ScheduleExceptionRow::id).reversed())
                .toList();
    }

    /** Test hook: drop every row. */
    public void clear() {
        rows.clear();
        work.remove();
    }

    private Work requireWork() {
        Work w = work.get();
        if (w == null) {
            throw new IllegalStateException("schedule_exception write outside a work unit");
        }
        return w;
    }

    private static final class Work {
        private final List<Runnable> undos = new ArrayList<>();
        private int depth;
    }
}
