package com.jisuodashi.level;

import java.util.List;

/** 晋升口径的唯一出处。 */
public final class LevelPolicy {

    private LevelPolicy() {
    }

    /**
     * 达标的最高一档，没有则返回空。逐档从高往低试：门槛是累计评价数 + 累计好评率，
     * 两个都过才算，且必须严格高于当前档 —— 降级不在 P1 范围，系统只提名晋升。
     */
    public static String eligibleLevel(
            String currentLevel,
            LevelModels.LifetimeStat stat,
            List<LevelModels.LevelConfig> configs) {
        int currentSort = sortOf(currentLevel, configs);
        LevelModels.LevelConfig best = null;
        for (LevelModels.LevelConfig c : configs) {
            if (c.status() != 1 || c.sortNo() <= currentSort) {
                continue;
            }
            if (stat.reviewCount() < c.minReviewCount()
                    || stat.positiveRateX100() < c.minPositiveRateX100()) {
                continue;
            }
            if (best == null || c.sortNo() > best.sortNo()) {
                best = c;
            }
        }
        return best == null ? null : best.level();
    }

    public static int sortOf(String level, List<LevelModels.LevelConfig> configs) {
        for (LevelModels.LevelConfig c : configs) {
            if (c.level().equals(level)) {
                return c.sortNo();
            }
        }
        return 0;
    }
}
