package com.jisuodashi.scoreboard;

import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.catalog.CatalogModels;
import com.jisuodashi.catalog.CatalogRepository;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.growth.GrowthDtos;
import com.jisuodashi.growth.GrowthModels;
import com.jisuodashi.growth.GrowthPolicy;
import com.jisuodashi.growth.GrowthService;
import com.jisuodashi.growth.GrowthStore;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import com.jisuodashi.membership.MembershipModels;
import com.jisuodashi.membership.MembershipPolicy;
import com.jisuodashi.membership.MembershipService;
import com.jisuodashi.membership.MembershipStore;
import com.jisuodashi.review.OrderReview;
import com.jisuodashi.review.ReviewStore;
import com.jisuodashi.staff.StaffTherapistLookup;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 老师月度成果与全国排名。
 *
 * <p>六项指标：上课量、销售额、新客好评率、续费率、转成交率、回访完成度。
 * 每项各排一次名 —— 合成一个"综合分"会把权重藏起来，老师看不懂自己该改什么。
 */
@Service
public class ScoreboardService {

    /** 一位老师的月度原始指标。排名就是把全国的这些行按某一列排序。 */
    record Metrics(
            long therapistId,
            String name,
            long storeId,
            int lessons,
            long salesFen,
            int newReviewCount,
            int newPositiveCount,
            int renewBase,
            int renewHit,
            int trialCount,
            int convertedCount,
            int followUpDue,
            int followUpDone
    ) {
        int positiveRateX100() {
            return GrowthPolicy.rateX100(newPositiveCount, newReviewCount);
        }

        int renewRateX100() {
            return GrowthPolicy.rateX100(renewHit, renewBase);
        }

        int convertRateX100() {
            return GrowthPolicy.rateX100(convertedCount, trialCount);
        }

        int followUpRateX100() {
            return GrowthPolicy.rateX100(followUpDone, followUpDue);
        }
    }

    private static final List<String> EARNED = List.of("COMPLETED", "REVIEWED");

    private final CatalogRepository catalog;
    private final SlotOccupyService occupy;
    private final MembershipStore membershipStore;
    private final MembershipService membership;
    private final GrowthStore growthStore;
    private final GrowthService growth;
    private final ReviewStore reviews;
    private final StaffTherapistLookup therapists;
    private final AppClock clock;

    public ScoreboardService(
            CatalogRepository catalog,
            SlotOccupyService occupy,
            MembershipStore membershipStore,
            MembershipService membership,
            GrowthStore growthStore,
            GrowthService growth,
            ReviewStore reviews,
            StaffTherapistLookup therapists,
            AppClock clock) {
        this.catalog = catalog;
        this.occupy = occupy;
        this.membershipStore = membershipStore;
        this.membership = membership;
        this.growthStore = growthStore;
        this.growth = growth;
        this.reviews = reviews;
        this.therapists = therapists;
        this.clock = clock;
    }

    public GrowthDtos.ScoreboardResponse mine(String monthRaw) {
        CatalogModels.Therapist me = therapists.requireTherapist(AuthContext.requireStaff());
        LocalDate from = monthStart(monthRaw);
        LocalDate to = monthEnd(from);

        List<Metrics> all = allMetrics(from, to);
        Metrics mine = all.stream()
                .filter(m -> m.therapistId() == me.id())
                .findFirst()
                .orElse(new Metrics(me.id(), me.name(), me.homeStoreId(),
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0));

        List<GrowthDtos.RankRow> ranks = List.of(
                rank(all, mine, "lessons", "上课量",
                        Comparator.comparingInt(Metrics::lessons).reversed(),
                        mine.lessons() + " 节"),
                rank(all, mine, "sales", "销售额",
                        Comparator.comparingLong(Metrics::salesFen).reversed(),
                        yuan(mine.salesFen())),
                rank(all, mine, "positive", "新客好评率",
                        Comparator.comparingInt(Metrics::positiveRateX100).reversed(),
                        pct(mine.positiveRateX100())),
                rank(all, mine, "renew", "续费率",
                        Comparator.comparingInt(Metrics::renewRateX100).reversed(),
                        pct(mine.renewRateX100())),
                rank(all, mine, "convert", "转成交率",
                        Comparator.comparingInt(Metrics::convertRateX100).reversed(),
                        pct(mine.convertRateX100())));

        List<MembershipModels.Profile> members = membership.members(me.homeStoreId(), me.id());
        int profileRate = GrowthPolicy.rateX100(
                members.stream().filter(p -> p.coreIssue() != null && !p.coreIssue().isBlank()).count(),
                members.size());
        int compliance = members.isEmpty() ? 0 : (int) members.stream()
                .mapToInt(p -> growth.complianceX100(p.customerId(),
                        membership.plans(p.customerId()).stream()
                                .filter(x -> x.status() == MembershipModels.PLAN_RUNNING)
                                .findFirst().orElse(null)))
                .average().orElse(0);

        return new GrowthDtos.ScoreboardResponse(
                String.valueOf(me.id()), me.name(), from.toString().substring(0, 7),
                mine.lessons(), yuan(mine.salesFen()), mine.positiveRateX100(),
                mine.renewRateX100(), mine.convertRateX100(),
                mine.followUpRateX100(), profileRate, compliance,
                growth.presentDays(AuthContext.requireStaff().staffId(), from, to),
                ranks,
                GrowthPolicy.encourage(mine.lessons(), mine.positiveRateX100(), mine.followUpRateX100()));
    }

    /** 管理端：本店老师横向对比。按上课量排，与「全国排名」同一套指标。 */
    public List<GrowthDtos.ScoreboardResponse> storeBoard(long storeId, String monthRaw) {
        LocalDate from = monthStart(monthRaw);
        LocalDate to = monthEnd(from);
        return allMetrics(from, to).stream()
                .filter(m -> m.storeId() == storeId)
                .sorted(Comparator.comparingInt(Metrics::lessons).reversed())
                .map(m -> new GrowthDtos.ScoreboardResponse(
                        String.valueOf(m.therapistId()), m.name(), from.toString().substring(0, 7),
                        m.lessons(), yuan(m.salesFen()), m.positiveRateX100(),
                        m.renewRateX100(), m.convertRateX100(), m.followUpRateX100(),
                        0, 0, 0, List.of(), null))
                .toList();
    }

    private GrowthDtos.RankRow rank(
            List<Metrics> all, Metrics mine, String key, String label,
            Comparator<Metrics> order, String value) {
        List<Metrics> sorted = all.stream().sorted(order).toList();
        int idx = 0;
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).therapistId() == mine.therapistId()) {
                idx = i + 1;
                break;
            }
        }
        return new GrowthDtos.RankRow(key, label, value, idx, sorted.size());
    }

    /**
     * 全国口径：把所有在营门店的在职老师都算一遍。
     *
     * <p>刻意不走 StoreScope —— 排名的定义就是跨店。调用方用 {@code ranking:national}
     * 权限把门，且只把名次和本人指标返回出去，别店的明细不出这个方法。
     */
    private List<Metrics> allMetrics(LocalDate from, LocalDate to) {
        List<CatalogModels.Store> stores = catalog.listStores().stream()
                .filter(s -> s.status() == 1).toList();
        List<OrderReview> monthReviews = reviews.listSince(
                from.atStartOfDay(clock.clock().getZone()).toInstant());

        List<Metrics> out = new ArrayList<>();
        for (CatalogModels.Therapist t : catalog.listTherapists()) {
            if (t.status() != 1 || stores.stream().noneMatch(s -> s.id() == t.homeStoreId())) {
                continue;
            }
            List<BookingOrderRef> orders = occupy.listOrdersByTherapist(t.id(), from, to);
            int lessons = (int) orders.stream().filter(o -> EARNED.contains(o.status())).count();

            List<MembershipModels.Package> sold =
                    membershipStore.listPackagesSold(t.homeStoreId(), t.id(), from, to);
            long salesFen = sold.stream().mapToLong(MembershipModels.Package::priceFen).sum();

            // 新客好评：只算体验客的评价，老客的评价不进这个分母。
            List<OrderReview> mineReviews = monthReviews.stream()
                    .filter(r -> r.therapistId() == t.id())
                    .filter(r -> isNewCustomer(r.customerId()))
                    .toList();
            int newCount = mineReviews.size();
            int newPositive = (int) mineReviews.stream().filter(OrderReview::positive).count();
            // 外部平台认领的好评也计入，但只算审核通过的。
            List<GrowthModels.ExternalClaim> claims = growth.approvedClaims(t.id(), from, to);
            newCount += claims.size();
            newPositive += (int) claims.stream()
                    .filter(c -> c.rating() >= GrowthPolicy.POSITIVE_SCORE).count();

            // 续费率：本月到期的课包里，有几个人又买了新的。
            List<MembershipModels.Package> expiring = membershipStore
                    .listPackagesEnding(t.homeStoreId(), from, to);
            Map<Long, List<MembershipModels.Package>> byCustomer = sold.stream()
                    .collect(Collectors.groupingBy(MembershipModels.Package::customerId));
            int renewBase = expiring.size();
            int renewHit = (int) expiring.stream()
                    .filter(p -> byCustomer.containsKey(p.customerId())).count();

            // 转成交率：本月体验客里有几个买了正课。
            List<MembershipModels.Profile> members = membershipStore.listProfiles(t.homeStoreId(), t.id());
            List<MembershipModels.Profile> trials = members.stream()
                    .filter(p -> p.firstVisitOn() != null && inRange(p.firstVisitOn(), from, to))
                    .toList();
            int converted = (int) trials.stream().filter(p -> p.convertedOn() != null).count();

            List<GrowthModels.FollowUp> fus = growthStore.listFollowUpsByStaff(t.staffUserId(), from, to);
            LocalDate today = clock.today();
            int fuDue = (int) fus.stream()
                    .filter(f -> f.dueOn() == null || !f.dueOn().isAfter(today)).count();
            int fuDone = (int) fus.stream()
                    .filter(f -> f.dueOn() == null || !f.dueOn().isAfter(today))
                    .filter(f -> !f.pending()).count();

            out.add(new Metrics(t.id(), t.name(), t.homeStoreId(), lessons, salesFen,
                    newCount, newPositive, renewBase, renewHit,
                    trials.size(), converted, fuDue, fuDone));
        }
        return out;
    }

    private boolean isNewCustomer(long customerId) {
        return membership.profile(customerId)
                .map(MembershipModels.Profile::isTrialOnly)
                .orElse(true);
    }

    static boolean inRange(LocalDate d, LocalDate from, LocalDate to) {
        return d != null && !d.isBefore(from) && !d.isAfter(to);
    }

    public LocalDate monthStart(String raw) {
        if (raw == null || raw.isBlank()) {
            return clock.today().withDayOfMonth(1);
        }
        try {
            return LocalDate.parse(raw.trim() + "-01");
        } catch (RuntimeException e) {
            return clock.today().withDayOfMonth(1);
        }
    }

    public static LocalDate monthEnd(LocalDate start) {
        return start.withDayOfMonth(start.lengthOfMonth());
    }

    static String yuan(long fen) {
        return String.format("%.2f", fen / 100.0);
    }

    static String pct(int x100) {
        return (x100 / 100) + "%";
    }
}
