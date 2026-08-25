package com.jisuodashi.review;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReviewRepository {

    Optional<Review> findByOrderId(long orderId);

    List<Review> listByTherapistId(long therapistId, int limit);

    List<Review> listByCustomerId(long customerId);

    Review insert(Review review);

    /** Average score x100 over a therapist's reviews, or empty when there are none. */
    Optional<Integer> averageScoreX100(long therapistId);

    /**
     * 30 天窗口内的全部有效评价，供 {@link TherapistStatService} 日更重算。
     * 按技师分组是调用方的事 —— 一次全窗口扫描换掉「每技师一次聚合」的 N 次查询。
     */
    List<Review> listSince(Instant startAt);
}
