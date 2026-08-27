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
}
