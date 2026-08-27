package com.jisuodashi.review;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import com.jisuodashi.order.FireContext;
import com.jisuodashi.order.OrderEvent;
import com.jisuodashi.order.OrderStateMachine;
import com.jisuodashi.order.OrderStatus;
import com.jisuodashi.order.ReviewDraft;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

@Service
public class ReviewService {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final ReviewStore reviews;
    private final SlotOccupyService occupy;
    private final OrderStateMachine machine;
    private final TherapistStatService statService;
    private final AppClock clock;

    public ReviewService(
            ReviewStore reviews,
            SlotOccupyService occupy,
            OrderStateMachine machine,
            TherapistStatService statService,
            AppClock clock) {
        this.reviews = reviews;
        this.occupy = occupy;
        this.machine = machine;
        this.statService = statService;
        this.clock = clock;
    }

    /**
     * 重复提交返回已有评价而不是报错：客户端超时重发、双击提交都会走到这里，
     * 一个「已评价」的 40904 对客户来说和失败没有区别。幂等由 {@code uk_review_order} 兜底。
     */
    public ReviewDtos.ReviewDetail submit(
            long customerId, long orderId, ReviewDtos.SubmitReviewRequest request) {
        BookingOrderRef order = requireOwnOrder(customerId, orderId);

        Optional<OrderReview> existing = reviews.findByOrderId(orderId);
        if (existing.isPresent()) {
            return detail(existing.get());
        }

        OrderStatus status = OrderStatus.parse(order.status());
        if (status != OrderStatus.COMPLETED) {
            throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "只有已完成的订单可以评价");
        }
        if (order.serviceDate() != null
                && order.serviceDate().plusDays(ReviewPolicy.REVIEW_WINDOW_DAYS).isBefore(clock.today())) {
            throw new ApiException(
                    ErrorCodes.ILLEGAL_TRANSITION,
                    "服务结束超过 " + ReviewPolicy.REVIEW_WINDOW_DAYS + " 天，不能再评价");
        }

        ReviewDraft draft = draft(request);
        try {
            machine.fire(orderId, OrderEvent.REVIEW, FireContext.customer(customerId).withReview(draft));
        } catch (ApiException e) {
            // 并发双提交：一方 CAS 赢，另一方撞 40904。有评价行就说明赢家已经写完了。
            if (e.getCode() == ErrorCodes.ILLEGAL_TRANSITION) {
                Optional<OrderReview> raced = reviews.findByOrderId(orderId);
                if (raced.isPresent()) {
                    return detail(raced.get());
                }
            }
            throw e;
        }

        OrderReview saved = reviews.findByOrderId(orderId)
                .orElseThrow(() -> new ApiException(ErrorCodes.INTERNAL, "评价写入失败"));
        statService.onReviewed(saved.therapistId(), saved.score(), saved.positive());
        return detail(saved);
    }

    public ReviewDtos.ReviewDetail get(long customerId, long orderId) {
        requireOwnOrder(customerId, orderId);
        return reviews.findByOrderId(orderId)
                .map(this::detail)
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "尚未评价"));
    }

    private BookingOrderRef requireOwnOrder(long customerId, long orderId) {
        BookingOrderRef order = occupy.findOrderById(orderId);
        if (order == null) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "订单不存在");
        }
        // 不存在与不属于你，对外一律 40401：否则枚举 orderId 就能探出哪些单号是真的。
        if (order.customerId() != customerId) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "订单不存在");
        }
        return order;
    }

    private ReviewDraft draft(ReviewDtos.SubmitReviewRequest request) {
        int score = request.score();
        String tags = normalizeTags(request.tags(), score);
        String content = normalizeContent(request.content());
        boolean anonymous = Boolean.TRUE.equals(request.anonymous());
        return new ReviewDraft(score, tags, content, anonymous);
    }

    /** 标签白名单 + 分档校验，去重保序，逗号拼存。 */
    private String normalizeTags(List<String> raw, int score) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        List<String> kept = new ArrayList<>(new LinkedHashSet<>(raw));
        if (kept.size() > ReviewPolicy.MAX_TAGS) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "最多选择 " + ReviewPolicy.MAX_TAGS + " 个标签");
        }
        for (String tag : kept) {
            if (!ReviewPolicy.knownTag(tag)) {
                throw new ApiException(ErrorCodes.BAD_REQUEST, "未知标签 " + tag);
            }
            if (!ReviewPolicy.tagMatchesScore(tag, score)) {
                throw new ApiException(ErrorCodes.BAD_REQUEST, "标签与评分不匹配 " + tag);
            }
        }
        return String.join(",", kept);
    }

    private String normalizeContent(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > ReviewPolicy.MAX_CONTENT_LENGTH) {
            throw new ApiException(
                    ErrorCodes.BAD_REQUEST, "评价内容不超过 " + ReviewPolicy.MAX_CONTENT_LENGTH + " 字");
        }
        return trimmed;
    }

    private ReviewDtos.ReviewDetail detail(OrderReview r) {
        return new ReviewDtos.ReviewDetail(
                String.valueOf(r.orderId()),
                String.valueOf(r.therapistId()),
                r.score(),
                r.positive(),
                splitTags(r.tags()),
                r.content(),
                r.anonymous(),
                r.createdAt() == null
                        ? null
                        : ISO.format(r.createdAt().atZone(AppClock.SHANGHAI).toOffsetDateTime()));
    }

    static List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return List.of(tags.split(","));
    }
}
