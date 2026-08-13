package com.jisuodashi.auth;

public interface RelatedRecordsRepository {

    void reassignBookings(long fromCustomerId, long toCustomerId);

    void reassignSessions(long fromCustomerId, long toCustomerId);

    void reassignServiceRecords(long fromCustomerId, long toCustomerId);

    void insertCollisionTask(String phoneHash);

    void insertMergeAudit(long fromId, long toId);

    default void addBooking(long orderId, long customerId) {
    }

    default void addSession(long sessionId, long customerId) {
    }

    default void addServiceRecord(long recordId, long customerId) {
    }

    default java.util.List<Long> bookingCustomerIds() {
        return java.util.List.of();
    }

    default java.util.List<Long> sessionSubjectIds() {
        return java.util.List.of();
    }

    default java.util.List<Long> serviceRecordCustomerIds() {
        return java.util.List.of();
    }

    default java.util.List<HumanTask> humanTasks() {
        return java.util.List.of();
    }

    default java.util.List<AuditEvent> audits() {
        return java.util.List.of();
    }

    default void clear() {
    }
}
