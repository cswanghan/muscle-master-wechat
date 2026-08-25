package com.jisuodashi.review;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReviewRepository#listSince} 的窗口边界。日更重算靠它取 30 天评价，
 * 边界差一天就是技师卡上的数字差一批。
 */
class ReviewWindowQueryTest {

    private static final Instant START = Instant.parse("2026-07-26T00:00:00Z");

    @Test
    void windowStartIsInclusiveAndEarlierRowsAreExcluded() {
        InMemoryReviewRepository repo = new InMemoryReviewRepository();
        repo.insert(review(1L, START.minusMillis(1)));
        repo.insert(review(2L, START));
        repo.insert(review(3L, START.plusSeconds(86400)));

        List<Long> ids = repo.listSince(START).stream().map(Review::id).sorted().toList();

        assertThat(ids).as("窗口起点算在内，早一毫秒的不算").containsExactly(2L, 3L);
    }

    @Test
    void windowSpansEveryTherapistNotJustOne() {
        // 日更是一次全窗口扫描后在内存里分组，不是每技师查一次。
        InMemoryReviewRepository repo = new InMemoryReviewRepository();
        repo.insert(new Review(1L, 1L, 901L, 401L, 3100L, 5, null, null, START.plusSeconds(60)));
        repo.insert(new Review(2L, 2L, 902L, 402L, 3100L, 4, null, null, START.plusSeconds(60)));

        assertThat(repo.listSince(START)).extracting(Review::therapistId)
                .containsExactlyInAnyOrder(401L, 402L);
    }

    private static Review review(long id, Instant createdAt) {
        return new Review(id, id, 901L, 401L, 3100L, 5, null, null, createdAt);
    }
}
