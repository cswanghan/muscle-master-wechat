package com.jisuodashi.level;

import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.catalog.CatalogModels;
import com.jisuodashi.catalog.CatalogRepository;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.review.ReviewStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 晋升只提名，不生效：扫描写 {@code status=0} 的待确认行，
 * {@code therapist.level} 要等 admin 确认才动（2026-08-25 口径）。
 */
@Service
public class LevelService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final LevelStore levels;
    private final ReviewStore reviews;
    private final CatalogRepository catalog;
    private final SnowflakeIdGenerator ids;
    private final AppClock clock;

    public LevelService(
            LevelStore levels,
            ReviewStore reviews,
            CatalogRepository catalog,
            SnowflakeIdGenerator ids,
            AppClock clock) {
        this.levels = levels;
        this.reviews = reviews;
        this.catalog = catalog;
        this.ids = ids;
        this.clock = clock;
    }

    /** 日更 job 与 admin 手动触发共用。同一技师同一目标档已有待确认行时跳过。 */
    public LevelDtos.ScanResponse scan() {
        List<LevelModels.LevelConfig> configs = levels.listConfigs();
        int scanned = 0;
        int proposed = 0;
        for (CatalogModels.Therapist t : catalog.listTherapists()) {
            if (t.status() != 1) {
                continue;
            }
            scanned++;
            ReviewStore.Lifetime life = reviews.lifetimeOf(t.id());
            LevelModels.LifetimeStat stat =
                    new LevelModels.LifetimeStat(t.id(), life.reviewCount(), life.positiveCount());
            String target = LevelPolicy.eligibleLevel(t.level(), stat, configs);
            if (target == null || levels.hasPending(t.id(), target)) {
                continue;
            }
            levels.insert(new LevelModels.LevelLog(
                    ids.nextId(), t.id(), t.level(), target,
                    LevelModels.REASON_AUTO_ELIGIBLE, null,
                    snapshot(stat), LevelModels.STATUS_PENDING,
                    Instant.now(clock.clock()), null));
            proposed++;
        }
        return new LevelDtos.ScanResponse(scanned, proposed);
    }

    public LevelDtos.PendingListResponse listPending() {
        List<LevelDtos.PendingItem> items = new ArrayList<>();
        List<LevelModels.LevelConfig> configs = levels.listConfigs();
        for (LevelModels.LevelLog log : levels.listByStatus(LevelModels.STATUS_PENDING)) {
            ReviewStore.Lifetime life = reviews.lifetimeOf(log.therapistId());
            LevelModels.LifetimeStat stat =
                    new LevelModels.LifetimeStat(log.therapistId(), life.reviewCount(), life.positiveCount());
            items.add(new LevelDtos.PendingItem(
                    String.valueOf(log.id()),
                    String.valueOf(log.therapistId()),
                    nameOf(log.therapistId()),
                    log.fromLevel(),
                    log.toLevel(),
                    displayNameOf(log.toLevel(), configs),
                    stat.reviewCount(),
                    stat.positiveRateX100(),
                    log.createdAt() == null ? null : ISO.format(log.createdAt())));
        }
        return new LevelDtos.PendingListResponse(items, items.size());
    }

    public LevelDtos.DecideResponse confirm(String logIdRaw) {
        return decide(logIdRaw, true);
    }

    public LevelDtos.DecideResponse reject(String logIdRaw) {
        return decide(logIdRaw, false);
    }

    private LevelDtos.DecideResponse decide(String logIdRaw, boolean approve) {
        long staffId = AuthContext.requireStaff().staffId();
        long logId = parseId(logIdRaw);
        LevelModels.LevelLog log = levels.findById(logId)
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "晋升记录不存在"));
        if (log.status() != LevelModels.STATUS_PENDING) {
            throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "该记录已处理");
        }
        Instant now = Instant.now(clock.clock());
        int status = approve ? LevelModels.STATUS_APPLIED : LevelModels.STATUS_REJECTED;
        levels.decide(logId, status, staffId, now);
        if (approve) {
            applyLevel(log.therapistId(), log.toLevel());
        }
        return new LevelDtos.DecideResponse(
                String.valueOf(logId),
                String.valueOf(log.therapistId()),
                log.fromLevel(),
                log.toLevel(),
                approve ? LevelModels.REASON_ADMIN_CONFIRM : LevelModels.REASON_ADMIN_REJECT,
                ISO.format(now));
    }

    /** P1 只改展示用的 level；价格不动（P2 才把 priceDeltaFen 接进 Pricing）。 */
    private void applyLevel(long therapistId, String toLevel) {
        catalog.listTherapists().stream()
                .filter(t -> t.id() == therapistId)
                .findFirst()
                .ifPresent(t -> catalog.upsertTherapist(new CatalogModels.Therapist(
                        t.id(), t.staffUserId(), t.employeeNo(), t.name(), t.homeStoreId(),
                        toLevel, t.avatarUrl(), t.intro(), t.ratingX100(), t.status(),
                        t.projectIds(), t.symptomIds())));
    }

    private String nameOf(long therapistId) {
        return catalog.listTherapists().stream()
                .filter(t -> t.id() == therapistId)
                .findFirst()
                .map(CatalogModels.Therapist::name)
                .orElse("技师");
    }

    private static String displayNameOf(String level, List<LevelModels.LevelConfig> configs) {
        return configs.stream()
                .filter(c -> c.level().equals(level))
                .findFirst()
                .map(LevelModels.LevelConfig::displayName)
                .orElse(level);
    }

    private static String snapshot(LevelModels.LifetimeStat stat) {
        return "{\"reviewCount\":" + stat.reviewCount()
                + ",\"positiveCount\":" + stat.positiveCount()
                + ",\"positiveRateX100\":" + stat.positiveRateX100() + "}";
    }

    private static long parseId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "id 无效");
        }
    }
}
