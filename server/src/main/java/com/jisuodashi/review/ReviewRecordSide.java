package com.jisuodashi.review;

import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import com.jisuodashi.order.ReviewDraft;
import com.jisuodashi.order.ReviewSide;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** {@code COMPLETED -> REVIEWED} 的 side：评价行与状态 CAS 同事务落库。 */
@Component
public class ReviewRecordSide implements ReviewSide {

    private final ReviewStore reviews;
    private final SnowflakeIdGenerator ids;

    public ReviewRecordSide(ReviewStore reviews, SnowflakeIdGenerator ids) {
        this.reviews = reviews;
        this.ids = ids;
    }

    @Override
    public void insertReview(BookingOrderRef order, ReviewDraft draft, Instant now) {
        reviews.insert(new OrderReview(
                ids.nextId(),
                order.id(),
                order.customerId(),
                order.therapistId(),
                order.storeId(),
                draft.score(),
                ReviewPolicy.positive(draft.score()),
                draft.tags(),
                draft.content(),
                draft.anonymous(),
                1,
                now));
    }
}
