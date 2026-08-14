package com.jisuodashi.inventory.persist;

import com.jisuodashi.inventory.ScheduleExceptionStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Work units are no-ops: the surrounding Spring transaction is the unit of work. */
@Repository
@Profile("!dev")
public class MybatisScheduleExceptionStore implements ScheduleExceptionStore {

    private final ScheduleExceptionMapper mapper;

    public MybatisScheduleExceptionStore(ScheduleExceptionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void beginWork() {
    }

    @Override
    public void commitWork() {
    }

    @Override
    public void rollbackWork() {
    }

    @Override
    public void insert(ScheduleExceptionRow row) {
        mapper.insert(row);
    }

    @Override
    public ScheduleExceptionRow findById(long id) {
        return mapper.findById(id);
    }

    @Override
    public ScheduleExceptionRow lockById(long id) {
        return mapper.lockById(id);
    }

    @Override
    public int casStatus(long id, String expectedStatus, String nextStatus, LocalDateTime now) {
        return mapper.casStatus(id, expectedStatus, nextStatus, now);
    }

    @Override
    public List<ScheduleExceptionRow> list(
            List<Long> storeIds, LocalDate from, LocalDate to, String status) {
        List<ScheduleExceptionRow> rows = mapper.list(storeIds, from, to, status);
        return rows == null ? List.of() : rows;
    }
}
