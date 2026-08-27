package com.jisuodashi.level;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** dev / 测试。配置初值与 V7 的 INSERT 保持一致。 */
@Repository
@Profile("dev")
public class InMemoryLevelStore implements LevelStore {

    private final List<LevelModels.LevelConfig> configs = List.of(
            new LevelModels.LevelConfig("JUNIOR", 1, "初级", 0, 0, 0, 1),
            new LevelModels.LevelConfig("MIDDLE", 2, "中级", 2000, 30, 8500, 1),
            new LevelModels.LevelConfig("SENIOR", 3, "资深", 4000, 80, 9000, 1),
            new LevelModels.LevelConfig("CHIEF", 4, "首席", 8000, 200, 9500, 1));

    private final CopyOnWriteArrayList<LevelModels.LevelLog> logs = new CopyOnWriteArrayList<>();

    @Override
    public List<LevelModels.LevelConfig> listConfigs() {
        return configs;
    }

    @Override
    public List<LevelModels.LevelLog> listByStatus(int status) {
        return logs.stream()
                .filter(l -> l.status() == status)
                .sorted(Comparator.comparing(LevelModels.LevelLog::createdAt).reversed())
                .toList();
    }

    @Override
    public Optional<LevelModels.LevelLog> findById(long id) {
        return logs.stream().filter(l -> l.id() == id).findFirst();
    }

    @Override
    public boolean hasPending(long therapistId, String toLevel) {
        return logs.stream().anyMatch(l -> l.therapistId() == therapistId
                && l.toLevel().equals(toLevel)
                && l.status() == LevelModels.STATUS_PENDING);
    }

    @Override
    public void insert(LevelModels.LevelLog log) {
        logs.add(log);
    }

    @Override
    public void decide(long id, int status, long operatorId, Instant decidedAt) {
        for (int i = 0; i < logs.size(); i++) {
            LevelModels.LevelLog l = logs.get(i);
            if (l.id() == id) {
                logs.set(i, new LevelModels.LevelLog(l.id(), l.therapistId(), l.fromLevel(),
                        l.toLevel(), l.reason(), operatorId, l.snapshotJson(), status,
                        l.createdAt(), decidedAt));
                return;
            }
        }
    }
}
