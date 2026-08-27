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

    /** 演示历史评价的 orderId 段，与真实雪花 ID 不重叠。 */
    private static final long SEED_ORDER_BASE = 9_100_000_000_000_000_000L;
    private static final long SEED_CUSTOMER = 9_100_000_000_000_000_001L;

    private final List<OrderReview> reviews = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<Long, OrderReview> byOrder = new ConcurrentHashMap<>();

    public InMemoryReviewStore() {
        seedHistory();
    }

    /**
     * 让等级晋升有东西可演示：周可（JUNIOR）攒够中级门槛，陈默（MIDDLE）差一点。
     *
     * <p>时间刻意放在 30 天窗口之外 —— 累计口径吃得到（晋升按资历），30 天统计吃不到
     * （卡片仍显示近况）。这正是两个口径分开的意义，混在一起就看不出区别了。
     */
    private void seedHistory() {
        Instant longAgo = Instant.parse("2026-05-01T10:00:00Z");
        // 周可 403：40 条 / 35 好评 = 87.5%，过中级门槛（30 条 / 85%）
        seedFor(3_100_000_000_000_000_403L, 40, 35, longAgo, 0);
        // 陈默 402：60 条 / 52 好评 = 86.7%，条数够资深但好评率差 90% 门槛
        seedFor(3_100_000_000_000_000_402L, 60, 52, longAgo, 100);
    }

    private void seedFor(long therapistId, int count, int positive, Instant at, int offset) {
        for (int i = 0; i < count; i++) {
            int score = i < positive ? 5 : 3;
            long orderId = SEED_ORDER_BASE + offset + i;
            OrderReview r = new OrderReview(
                    orderId, orderId, SEED_CUSTOMER, therapistId,
                    3_100_000_000_000_000_001L, score, ReviewPolicy.positive(score),
                    null, null, true, 1, at.plusSeconds(i));
            byOrder.put(orderId, r);
            reviews.add(r);
        }
    }

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
