package com.jisuodashi.review;

import java.time.Instant;
import java.util.List;

/**
 * 回头判定的数据源：{@code service_record} 里真正做完的服务段。
 *
 * <p>刻意返回**全历史**明细而不是窗口内聚合结果 —— 首访序号必须在全历史上算再按窗口过滤，
 * 反过来先截窗口再排序，会把窗口外首访的老客误判成首访，回头数系统性偏低。
 * 聚合逻辑收在 {@link TherapistStatCalculator} 一处，dev/prod 共用同一条代码路径。
 */
public interface ServedVisitSource {

    List<ServedVisit> listCompletedVisits();

    record ServedVisit(long therapistId, long customerId, Instant startedAt) {
    }
}
