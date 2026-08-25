package com.jisuodashi.review;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface TherapistStatStore {

    /** 读侧：一次主键批量查，不逐人聚合。 */
    Map<Long, TherapistStat> findAll(Collection<Long> therapistIds);

    /** 日更 job 全量重算后整表替换。 */
    void replaceAll(List<TherapistStat> stats);

    /**
     * 评价写入后的增量刷：只加评价那半边。新评价要立刻可见，不能等到第二天。
     *
     * <p>服务/回头那半边留给日更 job —— 「回头」要看客户在该技师处的全历史首访序号，
     * 单笔订单完成时算不出来，硬算就要在热路径上跑一次全表扫描。
     */
    void bumpReview(long therapistId, int score, boolean positive, Instant now);
}
