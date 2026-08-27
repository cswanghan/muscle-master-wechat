package com.jisuodashi.review;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReviewStore {

    /** 落库；{@code uk_review_order} 是幂等兜底，重复插入抛异常由调用方转成「已评价」。 */
    void insert(OrderReview review);

    Optional<OrderReview> findByOrderId(long orderId);

    /** 统计窗口内的全部有效评价（30 天量级，直接拉行即可）。 */
    List<OrderReview> listSince(Instant since);

    /** 某技师的评价列表，新的在前。 */
    List<OrderReview> listByTherapist(long therapistId, int limit);

    /**
     * 累计口径（不设窗口），等级晋升用。展示走 30 天反映近况，晋升走累计反映资历 ——
     * 30 天门槛会让技师休一次长假就掉档。
     */
    Lifetime lifetimeOf(long therapistId);

    record Lifetime(int reviewCount, int positiveCount) {
        public static final Lifetime NONE = new Lifetime(0, 0);
    }
}
