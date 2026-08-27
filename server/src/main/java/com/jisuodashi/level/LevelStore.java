package com.jisuodashi.level;

import java.util.List;
import java.util.Optional;

public interface LevelStore {

    /** 按 sort_no 升序，只含启用档。 */
    List<LevelModels.LevelConfig> listConfigs();

    List<LevelModels.LevelLog> listByStatus(int status);

    Optional<LevelModels.LevelLog> findById(long id);

    /** 同一技师同一目标档只留一条待确认，避免日更 job 天天堆重复条目。 */
    boolean hasPending(long therapistId, String toLevel);

    void insert(LevelModels.LevelLog log);

    void decide(long id, int status, long operatorId, java.time.Instant decidedAt);
}
