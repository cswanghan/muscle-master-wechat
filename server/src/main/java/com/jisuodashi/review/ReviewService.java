package com.jisuodashi.review;

import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.catalog.CatalogModels;
import com.jisuodashi.catalog.CatalogRepository;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
public class ReviewService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private static final int MAX_CONTENT = 500;
    private static final int MAX_TAGS = 5;
    private static final int DEFAULT_LIMIT = 20;

    private final ReviewRepository reviews;
    private final SlotOccupyService occupy;
    private final CatalogRepository catalog;
    private final SnowflakeIdGenerator ids;
    private final AppClock clock;

    public ReviewService(
            ReviewRepository reviews,
            SlotOccupyService occupy,
            CatalogRepository catalog,
            SnowflakeIdGenerator ids,
            AppClock clock) {
        this.reviews = reviews;
        this.occupy = occupy;
        this.catalog = catalog;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * Only the customer who owns a COMPLETED order may review it, once. A second
     * attempt replays the existing review rather than erroring, so a retried tap
     * does not look like a failure.
     */
    public ReviewDtos.ReviewView create(String orderIdRaw, ReviewDtos.CreateRequest request) {
        long customerId = AuthContext.requireCustomer().subjectId();
        long orderId = parseId(orderIdRaw);
        if (request == null || request.score() == null) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "score 不能为空");
        }
        BookingOrderRef order = occupy.findOrderById(orderId);
        if (order == null || order.customerId() != customerId) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "订单不存在");
        }
        if (!"COMPLETED".equals(order.status())) {
            throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "服务完成后才能评价");
        }
        Optional<Review> existing = reviews.findByOrderId(orderId);
        if (existing.isPresent()) {
            return view(existing.get());
        }
        Review saved = reviews.insert(new Review(
                ids.nextId(),
                orderId,
                customerId,
                order.therapistId(),
                order.storeId(),
                request.score(),
                joinTags(request.tags()),
                trim(request.content()),
                Instant.now(clock.clock())));
        return view(saved);
    }

    public ReviewDtos.ReviewView ofOrder(String orderIdRaw) {
        long customerId = AuthContext.requireCustomer().subjectId();
        long orderId = parseId(orderIdRaw);
        Review r = reviews.findByOrderId(orderId)
                .filter(x -> x.customerId() == customerId)
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "尚未评价"));
        return view(r);
    }

    /** Public list for the therapist picker; the customer is masked. */
    public ReviewDtos.ReviewListResponse listByTherapist(String therapistIdRaw, Integer limit) {
        long therapistId = parseId(therapistIdRaw);
        List<ReviewDtos.ReviewView> items = reviews
                .listByTherapistId(therapistId, limit == null ? DEFAULT_LIMIT : limit)
                .stream().map(this::view).toList();
        return new ReviewDtos.ReviewListResponse(
                items, reviews.averageScoreX100(therapistId).orElse(null), items.size());
    }

    public ReviewDtos.ReviewListResponse mine() {
        long customerId = AuthContext.requireCustomer().subjectId();
        List<ReviewDtos.ReviewView> items = reviews.listByCustomerId(customerId)
                .stream().map(this::view).toList();
        return new ReviewDtos.ReviewListResponse(items, null, items.size());
    }

    private ReviewDtos.ReviewView view(Review r) {
        String name = catalog.listTherapists().stream()
                .filter(t -> t.id() == r.therapistId())
                .findFirst()
                .map(CatalogModels.Therapist::name)
                .orElse("技师");
        return new ReviewDtos.ReviewView(
                String.valueOf(r.id()),
                String.valueOf(r.orderId()),
                String.valueOf(r.therapistId()),
                name,
                r.score(),
                splitTags(r.tags()),
                r.content(),
                "****",
                r.createdAt() == null ? null : ISO.format(r.createdAt()));
    }

    private static String joinTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        return String.join(",", tags.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .limit(MAX_TAGS)
                .toList());
    }

    private static List<String> splitTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return List.of(raw.split(","));
    }

    private static String trim(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String s = content.trim();
        return s.length() > MAX_CONTENT ? s.substring(0, MAX_CONTENT) : s;
    }

    private static long parseId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "id 无效");
        }
    }
}
