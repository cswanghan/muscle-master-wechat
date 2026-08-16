package com.jisuodashi.review;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository {

    Optional<Review> findByOrderId(long orderId);

    List<Review> listByTherapistId(long therapistId, int limit);

    List<Review> listByCustomerId(long customerId);

    Review insert(Review review);

    /** Average score x100 over a therapist's reviews, or empty when there are none. */
    Optional<Integer> averageScoreX100(long therapistId);
}
