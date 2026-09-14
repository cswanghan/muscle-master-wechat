package com.jisuodashi.finance;

import com.jisuodashi.catalog.CatalogModels;
import com.jisuodashi.catalog.CatalogRepository;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.growth.GrowthModels;
import com.jisuodashi.growth.GrowthPolicy;
import com.jisuodashi.growth.GrowthService;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.inventory.SlotOccupyStore;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import com.jisuodashi.membership.MembershipModels;
import com.jisuodashi.membership.MembershipService;
import com.jisuodashi.membership.MembershipStore;
import com.jisuodashi.payment.Payment;
import com.jisuodashi.payment.PaymentStore;
import com.jisuodashi.payment.Refund;
import com.jisuodashi.staff.StaffTherapistLookup;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 六张月度报表。
 *
 * <p>贯穿全表的口径：**耗课才是收入**。卖课收的钱在「库存课」里作为负债躺着，
 * 上一节课才转一节的价值进收入。所以整体业绩表的收入行不等于当月现金流入。
 */
@Service
public class FinanceReportService {

    private static final List<String> EARNED = List.of("COMPLETED", "REVIEWED");

    private final FinanceStore store;
    private final MembershipStore membershipStore;
    private final MembershipService membership;
    private final CatalogRepository catalog;
    private final SlotOccupyService occupy;
    private final SlotOccupyStore orders;
    private final PaymentStore payments;
    private final GrowthService growth;
    private final StaffTherapistLookup therapists;
    private final AppClock clock;

    public FinanceReportService(
            FinanceStore store, MembershipStore membershipStore, MembershipService membership,
            CatalogRepository catalog, SlotOccupyService occupy, SlotOccupyStore orders,
            PaymentStore payments, GrowthService growth,
            StaffTherapistLookup therapists, AppClock clock) {
        this.store = store;
        this.membershipStore = membershipStore;
        this.membership = membership;
        this.catalog = catalog;
        this.occupy = occupy;
        this.orders = orders;
        this.payments = payments;
        this.growth = growth;
        this.therapists = therapists;
        this.clock = clock;
    }

    // ── 41 当月耗课收入 ──

    public FinanceDtos.ConsumeReport consumeReport(long storeId, LocalDate from, LocalDate to) {
        List<MembershipModels.Txn> txns = membershipStore.listConsumed(storeId, null, from, to);
        // 同一位会员被同一位老师上的课合成一行，报表才看得下去。
        Map<String, List<MembershipModels.Txn>> grouped = new LinkedHashMap<>();
        for (MembershipModels.Txn t : txns) {
            grouped.computeIfAbsent(t.customerId() + "@" + t.therapistId(), k -> new ArrayList<>()).add(t);
        }
        List<FinanceDtos.ConsumeRow> rows = new ArrayList<>();
        long total = 0;
        int lessons = 0;
        for (List<MembershipModels.Txn> g : grouped.values()) {
            MembershipModels.Txn head = g.getFirst();
            long unit = membershipStore.findPackage(head.memberPackageId())
                    .map(MembershipModels.Package::unitPriceFen).orElse(0L);
            int count = g.stream().mapToInt(t -> -t.deltaSessions()).sum();
            long amount = unit * count;
            total += amount;
            lessons += count;
            rows.add(new FinanceDtos.ConsumeRow(
                    growth.mask(head.customerId()), count, yuan(unit), yuan(amount),
                    head.therapistId() == null ? "—" : therapists.nameOf(head.therapistId())));
        }
        rows.sort(Comparator.comparing(FinanceDtos.ConsumeRow::therapistName));
        return new FinanceDtos.ConsumeReport(month(from), rows, yuan(total), lessons);
    }

    // ── 42 当月体验课 ──

    public FinanceDtos.TrialReport trialReport(long storeId, LocalDate from, LocalDate to) {
        List<BookingOrderRef> trials = occupy.listTrialOrders(storeId, from, to);
        List<FinanceDtos.TrialRow> rows = new ArrayList<>();
        long total = 0;
        int converted = 0;
        Map<String, int[]> byChannel = new LinkedHashMap<>();

        for (BookingOrderRef o : trials) {
            MembershipModels.Profile p = membership.profile(o.customerId()).orElse(null);
            String channel = p == null || p.channel() == null ? "OTHER" : p.channel();
            boolean conv = p != null && p.convertedOn() != null;
            if (conv) {
                converted++;
            }
            total += o.payableFen();
            int[] agg = byChannel.computeIfAbsent(channel, k -> new int[2]);
            agg[0]++;
            if (conv) {
                agg[1]++;
            }
            rows.add(new FinanceDtos.TrialRow(
                    o.serviceDate().toString(), growth.mask(o.customerId()),
                    therapists.nameOf(o.therapistId()),
                    channel, GrowthModels.channelLabel(channel), yuan(o.payableFen()), conv));
        }

        List<FinanceDtos.ChannelRow> channels = byChannel.entrySet().stream()
                .map(e -> new FinanceDtos.ChannelRow(
                        e.getKey(), GrowthModels.channelLabel(e.getKey()),
                        e.getValue()[0], e.getValue()[1],
                        GrowthPolicy.rateX100(e.getValue()[1], e.getValue()[0])))
                .sorted(Comparator.comparingInt(FinanceDtos.ChannelRow::count).reversed())
                .toList();

        return new FinanceDtos.TrialReport(
                month(from), rows, trials.size(), yuan(total), converted,
                GrowthPolicy.rateX100(converted, trials.size()), channels);
    }

    // ── 43 当月退费 ──

    public FinanceDtos.RefundReport refundReport(long storeId, LocalDate from, LocalDate to) {
        List<FinanceDtos.RefundRow> rows = new ArrayList<>();
        long total = 0;
        for (Refund r : payments.listRefundsBetween(from, to)) {
            BookingOrderRef o = orders.findOrderById(r.orderId());
            if (o == null || o.storeId() != storeId) {
                continue;
            }
            total += r.amountFen();
            rows.add(new FinanceDtos.RefundRow(
                    r.updatedAt().toLocalDate().toString(),
                    growth.mask(o.customerId()), yuan(r.amountFen()),
                    r.reasonCode(), FinanceModels.refundReasonLabel(r.reasonCode()),
                    r.liableTherapistId() == null
                            ? "—" : therapists.nameOf(r.liableTherapistId())));
        }
        return new FinanceDtos.RefundReport(month(from), rows, yuan(total), rows.size());
    }

    // ── 44 当月老师工资 ──

    public FinanceDtos.PayrollReport payrollReport(long storeId, LocalDate from, LocalDate to) {
        List<FinanceDtos.PayrollRow> rows = new ArrayList<>();
        long grand = 0;
        for (CatalogModels.Therapist t : catalog.listTherapists()) {
            if (t.homeStoreId() != storeId || t.status() != 1) {
                continue;
            }
            FinanceModels.Payroll cfg = store.findPayroll(t.id()).orElse(null);
            long base = cfg == null ? FinancePolicy.DEFAULT_BASE_SALARY_FEN : cfg.baseSalaryFen();
            long fee = cfg == null ? FinancePolicy.DEFAULT_LESSON_FEE_FEN : cfg.lessonFeeFen();
            int rate = cfg == null || cfg.saleRateX100() <= 0
                    ? FinancePolicy.DEFAULT_SALE_RATE_X100 : cfg.saleRateX100();

            int lessons = (int) occupy.listOrdersByTherapist(t.id(), from, to).stream()
                    .filter(o -> EARNED.contains(o.status())).count();
            long sales = membershipStore.listPackagesSold(storeId, t.id(), from, to).stream()
                    .mapToLong(MembershipModels.Package::priceFen).sum();
            long saleComm = sales * rate / 10_000;
            long pay = FinancePolicy.payrollFen(base, fee, lessons, rate, sales);
            grand += pay;

            rows.add(new FinanceDtos.PayrollRow(
                    String.valueOf(t.id()), t.name(),
                    growth.presentDays(t.staffUserId(), from, to), lessons,
                    yuan(fee * lessons), yuan(sales), rate, yuan(saleComm),
                    yuan(base), yuan(pay)));
        }
        rows.sort(Comparator.comparing(FinanceDtos.PayrollRow::therapistName));
        return new FinanceDtos.PayrollReport(month(from), rows, yuan(grand));
    }

    // ── 45 库存课 ──

    public FinanceDtos.InventoryReport inventoryReport(long storeId) {
        LocalDate today = clock.today();
        LocalDate soon = today.plusDays(GrowthPolicy.EXPIRING_SOON_DAYS);
        List<FinanceDtos.InventoryRow> rows = new ArrayList<>();
        long totalValue = 0;
        long expiringValue = 0;
        int totalSessions = 0;

        for (MembershipModels.Profile p : membershipStore.listProfiles(storeId, null)) {
            for (MembershipModels.Package pkg : membershipStore.listPackagesByCustomer(p.customerId())) {
                if (!pkg.usableOn(today)) {
                    continue;
                }
                long value = FinancePolicy.inventoryValueFen(
                        pkg.remainingSessions(), pkg.unitPriceFen());
                boolean expiring = pkg.expireOn() != null && !pkg.expireOn().isAfter(soon);
                totalValue += value;
                totalSessions += pkg.remainingSessions();
                if (expiring) {
                    expiringValue += value;
                }
                rows.add(new FinanceDtos.InventoryRow(
                        growth.mask(p.customerId()), pkg.title(), pkg.remainingSessions(),
                        yuan(pkg.unitPriceFen()), yuan(value),
                        pkg.expireOn() == null ? null : pkg.expireOn().toString(), expiring));
            }
        }
        // 快到期的排最前：那部分负债最可能在下个月变成退费或投诉。
        rows.sort(Comparator.comparing((FinanceDtos.InventoryRow r) -> !r.expiringSoon())
                .thenComparing(FinanceDtos.InventoryRow::expireOn,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        return new FinanceDtos.InventoryReport(
                today.toString(), rows, totalSessions, yuan(totalValue), yuan(expiringValue));
    }

    // ── 46 门店整体业绩 ──

    public FinanceDtos.StoreReport storeReport(long storeId, LocalDate from, LocalDate to) {
        long consume = parseFen(consumeReport(storeId, from, to).totalYuan());
        long trial = parseFen(trialReport(storeId, from, to).totalYuan());
        // 单次付费：不走课包的现金单，避免与耗课收入重复计。
        long single = occupy.listOrdersByStore(storeId, from, to).stream()
                .filter(o -> EARNED.contains(o.status()))
                .filter(o -> orders.memberPackageIdOf(o.id()) == null)
                .filter(o -> !o.trial())
                .mapToLong(BookingOrderRef::payableFen).sum();
        long refund = parseFen(refundReport(storeId, from, to).totalYuan());
        long incomeTotal = consume + trial + single;

        long labor = parseFen(payrollReport(storeId, from, to).totalYuan());
        Map<String, Long> byCat = new LinkedHashMap<>();
        for (FinanceModels.Expense e : store.listExpenses(
                storeId, FinanceModels.STATUS_APPROVED, from, to)) {
            byCat.merge(e.category(), e.amountFen(), Long::sum);
        }
        // 人工成本由工资表算出来，不重复从支出里取 —— 两边都记会翻倍。
        byCat.put("LABOR", labor);

        List<FinanceDtos.AmountRow> income = List.of(
                new FinanceDtos.AmountRow("CONSUME", "耗课收入", yuan(consume)),
                new FinanceDtos.AmountRow("TRIAL", "体验课收入", yuan(trial)),
                new FinanceDtos.AmountRow("SINGLE", "单次付费收入", yuan(single)));

        List<FinanceDtos.AmountRow> expense = new ArrayList<>();
        long expenseTotal = 0;
        for (String cat : FinanceModels.CATEGORIES) {
            long v = byCat.getOrDefault(cat, 0L);
            expenseTotal += v;
            expense.add(new FinanceDtos.AmountRow(cat, FinanceModels.categoryLabel(cat), yuan(v)));
        }
        expense.add(new FinanceDtos.AmountRow("REFUND", "退费", yuan(refund)));
        expenseTotal += refund;

        long profit = incomeTotal - expenseTotal;
        return new FinanceDtos.StoreReport(
                month(from), income, yuan(incomeTotal), expense, yuan(expenseTotal),
                yuan(profit), profit >= 0);
    }

    // ── 39 客户报表 ──

    public FinanceDtos.CustomerReport customerReport(long storeId, String kind) {
        LocalDate today = clock.today();
        String k = kind == null || kind.isBlank() ? "renew" : kind.toLowerCase();
        List<FinanceDtos.CustomerRow> rows = new ArrayList<>();

        for (MembershipModels.Profile p : membershipStore.listProfiles(storeId, null)) {
            List<MembershipModels.Package> pkgs = membershipStore.listPackagesByCustomer(p.customerId());
            int remaining = pkgs.stream().filter(x -> x.usableOn(today))
                    .mapToInt(MembershipModels.Package::remainingSessions).sum();
            LocalDate expire = pkgs.stream()
                    .filter(x -> x.usableOn(today))
                    .map(MembershipModels.Package::expireOn)
                    .filter(java.util.Objects::nonNull)
                    .min(Comparator.naturalOrder()).orElse(null);
            LocalDate lastVisit = lastVisitOf(p.customerId());
            int dormant = lastVisit == null ? Integer.MAX_VALUE
                    : (int) java.time.temporal.ChronoUnit.DAYS.between(lastVisit, today);

            boolean hit = switch (k) {
                // 应续费：还有课但快没了，这时候提最有效
                case "renew" -> remaining > 0 && remaining <= GrowthPolicy.RETENTION_THRESHOLD_SESSIONS;
                // 到期未续：课包已经归零或过期，且没有新的
                case "expired" -> remaining <= 0 && !pkgs.isEmpty();
                // 长期未到访
                case "dormant" -> dormant >= GrowthPolicy.DORMANT_DAYS;
                default -> false;
            };
            if (!hit) {
                continue;
            }
            rows.add(new FinanceDtos.CustomerRow(
                    String.valueOf(p.customerId()), growth.mask(p.customerId()), p.wxNickname(),
                    remaining, expire == null ? null : expire.toString(),
                    lastVisit == null ? null : lastVisit.toString(),
                    dormant == Integer.MAX_VALUE ? -1 : dormant,
                    p.ownerTherapistId() == null ? "—" : therapists.nameOf(p.ownerTherapistId())));
        }
        rows.sort(Comparator.comparingInt(FinanceDtos.CustomerRow::remainingSessions));
        return new FinanceDtos.CustomerReport(k, customerKindLabel(k), rows, rows.size());
    }

    private LocalDate lastVisitOf(long customerId) {
        return occupy.listOrdersByCustomer(customerId).stream()
                .filter(o -> EARNED.contains(o.status()))
                .map(BookingOrderRef::serviceDate)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    static String customerKindLabel(String k) {
        return switch (k) {
            case "expired" -> "到期未续费";
            case "dormant" -> "长期未到访";
            default -> "应续费";
        };
    }

    static String month(LocalDate d) {
        return d.toString().substring(0, 7);
    }

    static String yuan(long fen) {
        return String.format("%.2f", fen / 100.0);
    }

    /** 报表之间互相引用时把元字符串转回分，避免各处重复算一遍。 */
    static long parseFen(String yuanStr) {
        try {
            return Math.round(Double.parseDouble(yuanStr) * 100);
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
