package com.jisuodashi.employment;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
@Profile("dev")
public class InMemoryEmploymentStore implements EmploymentStore {

    /**
     * 时间戳只到毫秒，同一毫秒内连着入职→离职→复职会打平，光按时间排会退回插入序。
     * 雪花 ID 单调递增，拿它兜底。
     */
    private static final Comparator<EmploymentModels.Log> NEWEST_FIRST =
            Comparator.comparing(EmploymentModels.Log::createdAt)
                    .thenComparingLong(EmploymentModels.Log::id)
                    .reversed();

    private final CopyOnWriteArrayList<EmploymentModels.Log> logs = new CopyOnWriteArrayList<>();

    @Override
    public void insert(EmploymentModels.Log log) {
        logs.add(log);
    }

    @Override
    public List<EmploymentModels.Log> listByStaff(long staffId) {
        return logs.stream()
                .filter(l -> l.staffId() == staffId)
                .sorted(NEWEST_FIRST)
                .toList();
    }

    @Override
    public List<EmploymentModels.Log> listRecent(int limit) {
        return logs.stream()
                .sorted(NEWEST_FIRST)
                .limit(Math.max(1, limit))
                .toList();
    }
}
