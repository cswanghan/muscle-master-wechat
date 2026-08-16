package com.jisuodashi.review;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
@Profile("dev")
public class InMemoryReviewRepository implements ReviewRepository {

    private final CopyOnWriteArrayList<Review> rows = new CopyOnWriteArrayList<>();

    @Override
    public Optional<Review> findByOrderId(long orderId) {
        return rows.stream().filter(r -> r.orderId() == orderId).findFirst();
    }

    @Override
    public List<Review> listByTherapistId(long therapistId, int limit) {
        return rows.stream()
                .filter(r -> r.therapistId() == therapistId)
                .sorted(Comparator.comparing(Review::createdAt).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public List<Review> listByCustomerId(long customerId) {
        return rows.stream()
                .filter(r -> r.customerId() == customerId)
                .sorted(Comparator.comparing(Review::createdAt).reversed())
                .toList();
    }

    @Override
    public Review insert(Review review) {
        rows.add(review);
        return review;
    }

    @Override
    public Optional<Integer> averageScoreX100(long therapistId) {
        List<Review> mine = rows.stream().filter(r -> r.therapistId() == therapistId).toList();
        if (mine.isEmpty()) {
            return Optional.empty();
        }
        int sum = mine.stream().mapToInt(Review::score).sum();
        return Optional.of(Math.round((float) sum * 100 / mine.size()));
    }
}
