package com.jisuodashi.common;

import java.util.Collection;
import java.util.Map;

/**
 * 技师统计读侧端口。由 {@code catalog}/{@code inventory} 消费、{@code review} 实现。
 *
 * <p>只有批量形态：展示接口一次返回整店技师，逐人一次聚合会把已在热路径上的
 * {@code GET /c/availability} 拖垮。
 */
public interface TherapistStatsPort {

    Map<Long, TherapistStatsView> statsFor(Collection<Long> therapistIds);
}
