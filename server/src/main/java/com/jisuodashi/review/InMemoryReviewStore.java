package com.jisuodashi.review;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
@Profile("dev")
public class InMemoryReviewStore implements ReviewStore {

    private final List<OrderReview> reviews = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<Long, OrderReview> byOrder = new ConcurrentHashMap<>();

    @Override
    public void insert(OrderReview review) {
        if (byOrder.putIfAbsent(review.orderId(), review) != null) {
            throw new IllegalStateException("duplicate review for order " + review.orderId());
        }
        reviews.add(review);
    }

    /** 测试夹具：每个用例都从空评价开始，否则跨用例的评价会污染统计断言。 */
    public void clear() {
        reviews.clear();
        byOrder.clear();
    }

    @Override
    public Optional<OrderReview> findByOrderId(long orderId) {
        return Optional.ofNullable(byOrder.get(orderId));
    }

    @Override
    public List<OrderReview> listSince(Instant since) {
        return reviews.stream()
                .filter(r -> r.status() == 1)
                .filter(r -> r.createdAt() != null && !r.createdAt().isBefore(since))
                .toList();
    }

    @Override
    public List<OrderReview> listByTherapist(long therapistId, int limit) {
        return reviews.stream()
                .filter(r -> r.status() == 1 && r.therapistId() == therapistId)
                .sorted(Comparator.comparing(OrderReview::createdAt).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public Lifetime lifetimeOf(long therapistId) {
        int count = 0;
        int positive = 0;
        for (OrderReview r : reviews) {
            if (r.therapistId() != therapistId || r.status() != 1) {
                continue;
            }
            count++;
            if (ReviewPolicy.positive(r.score())) {
                positive++;
            }
        }
        return new Lifetime(count, positive);
    }
}
