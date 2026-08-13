package com.jisuodashi.auth;

import com.jisuodashi.common.SnowflakeIdGenerator;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
public class InMemoryRelatedRecordsRepository implements RelatedRecordsRepository {

    private final SnowflakeIdGenerator ids;
    private final Clock clock;
    private final List<long[]> bookings = new CopyOnWriteArrayList<>();
    private final List<long[]> sessions = new CopyOnWriteArrayList<>();
    private final List<long[]> serviceRecords = new CopyOnWriteArrayList<>();
    private final List<HumanTask> tasks = new CopyOnWriteArrayList<>();
    private final List<AuditEvent> audits = new CopyOnWriteArrayList<>();

    public InMemoryRelatedRecordsRepository(SnowflakeIdGenerator ids, Clock clock) {
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public void reassignBookings(long fromCustomerId, long toCustomerId) {
        bookings.replaceAll(row -> row[1] == fromCustomerId ? new long[]{row[0], toCustomerId} : row);
    }

    @Override
    public void reassignSessions(long fromCustomerId, long toCustomerId) {
        sessions.replaceAll(row -> row[1] == fromCustomerId ? new long[]{row[0], toCustomerId} : row);
    }

    @Override
    public void reassignServiceRecords(long fromCustomerId, long toCustomerId) {
        serviceRecords.replaceAll(row -> row[1] == fromCustomerId ? new long[]{row[0], toCustomerId} : row);
    }

    @Override
    public void insertCollisionTask(String phoneHash) {
        String bizKey = "collide:" + phoneHash;
        boolean exists = tasks.stream().anyMatch(t -> bizKey.equals(t.getBizKey()));
        if (exists) {
            return;
        }
        HumanTask task = new HumanTask();
        task.setId(ids.nextId());
        task.setTaskType("CUSTOMER_COLLISION");
        task.setBizKey(bizKey);
        task.setTitle("客户身份冲突");
        task.setStatus("OPEN");
        task.setCreatedAt(Instant.now(clock));
        tasks.add(task);
    }

    @Override
    public void insertMergeAudit(long fromId, long toId) {
        audits.add(new AuditEvent(
                ids.nextId(),
                "CUSTOMER_MERGE",
                "CUSTOMER",
                toId,
                "{\"from\":" + fromId + "}",
                "{\"to\":" + toId + "}",
                Instant.now(clock)));
    }

    @Override
    public void addBooking(long orderId, long customerId) {
        bookings.add(new long[]{orderId, customerId});
    }

    @Override
    public void addSession(long sessionId, long customerId) {
        sessions.add(new long[]{sessionId, customerId});
    }

    @Override
    public void addServiceRecord(long recordId, long customerId) {
        serviceRecords.add(new long[]{recordId, customerId});
    }

    @Override
    public List<Long> bookingCustomerIds() {
        return bookings.stream().map(r -> r[1]).toList();
    }

    @Override
    public List<Long> sessionSubjectIds() {
        return sessions.stream().map(r -> r[1]).toList();
    }

    @Override
    public List<Long> serviceRecordCustomerIds() {
        return serviceRecords.stream().map(r -> r[1]).toList();
    }

    @Override
    public List<HumanTask> humanTasks() {
        return new ArrayList<>(tasks);
    }

    @Override
    public List<AuditEvent> audits() {
        return new ArrayList<>(audits);
    }

    @Override
    public void clear() {
        bookings.clear();
        sessions.clear();
        serviceRecords.clear();
        tasks.clear();
        audits.clear();
    }
}
