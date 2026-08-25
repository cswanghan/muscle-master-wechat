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

    ServiceRecord insertServiceRecord(
            long id, long orderId, long therapistId, long customerId, long storeId, Instant now);

    List<ServiceRecord> listServiceRecords(long orderId);

    /**
     * 全历史服务段，回头统计用（见 {@code ServedVisitSource}）。
     * 只有 dev 侧走这条 —— 生产在 SQL 里聚合，不把全表拉进内存。
     */
    default List<ServiceRecord> listAllServiceRecords() {
        return List.of();
    }

    void insertSystemNote(
            long id, long orderId, long storeId, long therapistId, long authorStaffId, String content, Instant now);
}
