package com.jisuodashi.membership;

import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.auth.Customer;
import com.jisuodashi.auth.CustomerRepository;
import com.jisuodashi.catalog.CatalogModels;
import com.jisuodashi.catalog.CatalogRepository;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.PhoneCrypto;
import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.rbac.StoreScope;
import com.jisuodashi.rbac.StoreScopeContext;
import com.jisuodashi.staff.StaffTherapistLookup;
import com.jisuodashi.order.PackageBookingPort;
import com.jisuodashi.order.SessionConsumeSide;
import com.jisuodashi.inventory.SlotOccupyStore;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 会员课包。余次是负债，耗课才是收入 —— 见 {@link MembershipPolicy}。
 */
@Service
public class MembershipService implements SessionConsumeSide, PackageBookingPort {

    private static final Logger log = LoggerFactory.getLogger(MembershipService.class);

    private final MembershipStore store;
    private final CatalogRepository catalog;
    private final SlotOccupyStore orders;
    private final CustomerRepository customers;
    private final PhoneCrypto crypto;
    private final StaffTherapistLookup therapists;
    private final SnowflakeIdGenerator ids;
    private final AppClock clock;

    public MembershipService(
            MembershipStore store,
            CatalogRepository catalog,
            SlotOccupyStore orders,
            CustomerRepository customers,
            PhoneCrypto crypto,
            StaffTherapistLookup therapists,
            SnowflakeIdGenerator ids,
            AppClock clock) {
        this.store = store;
        this.catalog = catalog;
        this.orders = orders;
        this.customers = customers;
        this.crypto = crypto;
        this.therapists = therapists;
        this.ids = ids;
        this.clock = clock;
    }

    // ---------- 卖课 ----------

    /**
     * 卖一个课包。{@code sellerTherapistId} 决定销售提成归谁，可空（前台自己卖的）。
     */
    public MembershipModels.Package sell(
            long customerId, long storeId, long projectId, int totalSessions,
            long priceFen, Long sellerTherapistId, Integer validDays, String requestId) {
        if (totalSessions <= 0) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "课时数必须大于 0");
        }
        if (priceFen < 0) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "金额不能为负");
        }
        CatalogModels.Project project = catalog.listProjects().stream()
                .filter(p -> p.id() == projectId)
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "课程类型不存在"));

        if (store.txnExists(requestId)) {
            // 重放：课包已经卖过了，再卖一次就是白送一份课时。
            return store.listPackagesByCustomer(customerId).stream()
                    .findFirst()
                    .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "课包不存在"));
        }

        Instant now = Instant.now(clock.clock());
        LocalDate today = clock.today();
        int days = validDays == null ? MembershipPolicy.DEFAULT_VALID_DAYS : validDays;
        long id = ids.nextId();
        MembershipModels.Package pkg = new MembershipModels.Package(
                id, customerId, storeId, projectId,
                project.name() + " " + totalSessions + " 次卡",
                totalSessions, 0, priceFen,
                MembershipPolicy.unitPriceFen(priceFen, totalSessions),
                sellerTherapistId, today,
                days <= 0 ? null : today.plusDays(days),
                MembershipModels.STATUS_ACTIVE, now, now);
        store.insertPackage(pkg);
        store.insertTxn(new MembershipModels.Txn(
                ids.nextId(), id, customerId, storeId, MembershipModels.TYPE_PURCHASE,
                totalSessions, null, sellerTherapistId, requestId, "购课", now));

        // 顺手建档：没有档案的会员在老师端的"我的会员"里就是隐身的。
        ensureProfile(customerId, storeId, sellerTherapistId);
        return pkg;
    }

    /** 前台入口：按手机号找会员，顾客必须已在小程序登录过。 */
    public MembershipDtos.SellPackageResponse sellByPhone(MembershipDtos.SellPackageRequest req) {
        long customerId = requireCustomerId(req.phone());
        long storeId = operatorStoreId();
        long projectId = parseId(req.projectId());
        Long seller = req.sellerTherapistId() == null || req.sellerTherapistId().isBlank()
                ? null : parseId(req.sellerTherapistId());
        String requestId = req.requestId() == null || req.requestId().isBlank()
                ? "sell:" + customerId + ":" + Instant.now(clock.clock()).toEpochMilli()
                : req.requestId();
        MembershipModels.Package pkg = sell(
                customerId, storeId, projectId, req.totalSessions(), req.priceFen(),
                seller, req.validDays(), requestId);
        return new MembershipDtos.SellPackageResponse(
                String.valueOf(pkg.id()), String.valueOf(customerId), pkg.title(),
                pkg.totalSessions(), yuan(pkg.unitPriceFen()),
                pkg.expireOn() == null ? null : pkg.expireOn().toString(),
                MembershipPolicy.saleCommissionFen(pkg.priceFen()));
    }

    /** 老师端改档案。老师只能改自己门店的会员，归属老师默认落到自己身上。 */
    public void saveProfileFromStaff(String customerIdRaw, MembershipDtos.ProfileRequest req) {
        CatalogModels.Therapist me = therapists.requireTherapist(AuthContext.requireStaff());
        long customerId = parseId(customerIdRaw);
        Long owner = req.ownerTherapistId() == null || req.ownerTherapistId().isBlank()
                ? me.id() : parseId(req.ownerTherapistId());
        saveProfile(customerId, me.homeStoreId(), owner, req.coreIssue(), req.remark());
    }

    private long requireCustomerId(String phone) {
        String e164 = PhoneCrypto.normalizeCnMobile(phone);
        if (e164 == null) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "手机号格式不对");
        }
        return customers.findByPhoneHash(crypto.hashE164(e164))
                .map(Customer::getId)
                .orElseThrow(() -> new ApiException(
                        ErrorCodes.NOT_FOUND, "该手机号还不是会员，先让顾客在小程序登录一次"));
    }

    /** 发卡门店取操作人的数据域；超管没有"自己的店"，得显式给。 */
    private static long operatorStoreId() {
        StoreScope scope = StoreScopeContext.get();
        if (scope == null || scope.storeIds().isEmpty()) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "请指定门店");
        }
        return scope.storeIds().getFirst();
    }

    private static long parseId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "id 无效");
        }
    }

    private static String yuan(long fen) {
        return String.format("%.2f", fen / 100.0);
    }

    // ---------- 约课绑课包 ----------

    /**
     * 校验课包可用并绑到订单上。
     *
     * <p>只校验 + 绑定，**不推状态**：状态机归 order 层，会员域反过来驱动它会造成
     * 构造期循环依赖（状态机持有本类的 SessionConsumeSide）。推状态由调用方做。
     *
     * <p>只校验"当下还有余次"，不做精确预占：预占要把"已约未上"的单也算进去，
     * 那是 P1 的事。现在的口子是同一张卡短时间连约多次可能超订，靠余次到 0 兜底。
     */
    @Override
    public void bindToOrder(long customerId, long orderId, String memberPackageIdRaw) {
        long packageId = parseId(memberPackageIdRaw);
        MembershipModels.Package pkg = store.findPackage(packageId)
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "课包不存在"));
        if (pkg.customerId() != customerId) {
            throw new ApiException(ErrorCodes.FORBIDDEN, "课包不属于该会员");
        }
        LocalDate today = clock.today();
        if (pkg.expiredOn(today)) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "课包已过期");
        }
        if (pkg.remainingSessions() <= 0) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "课时已用完，请先续课");
        }
        if (pkg.status() != MembershipModels.STATUS_ACTIVE) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "课包不可用");
        }
        orders.bindMemberPackage(orderId, packageId);
    }

    // ---------- 耗课 ----------

    /**
     * 课上完扣一次。订单没绑课包（按金额付的普通单）就什么都不做 —— 两条路并存。
     */
    @Override
    public void consume(long orderId) {
        BookingOrderRef order = orders.findOrderById(orderId);
        if (order == null) {
            return;
        }
        Long packageId = packageIdOf(order);
        if (packageId == null) {
            return;
        }
        String requestId = "consume:" + orderId;
        if (store.txnExists(requestId)) {
            return;
        }
        MembershipModels.Package pkg = store.findPackage(packageId).orElse(null);
        if (pkg == null) {
            log.warn("order {} bound to missing package {}", orderId, packageId);
            return;
        }
        Instant now = Instant.now(clock.clock());
        int used = pkg.usedSessions() + 1;
        // 扣到底就把状态翻成"已用完"，这样列表里不必每行再算一次余次。
        int status = used >= pkg.totalSessions()
                ? MembershipModels.STATUS_USED_UP : pkg.status();
        store.updatePackage(new MembershipModels.Package(
                pkg.id(), pkg.customerId(), pkg.storeId(), pkg.projectId(), pkg.title(),
                pkg.totalSessions(), used, pkg.priceFen(), pkg.unitPriceFen(),
                pkg.sellerTherapistId(), pkg.effectiveOn(), pkg.expireOn(),
                status, pkg.createdAt(), now));
        store.insertTxn(new MembershipModels.Txn(
                ids.nextId(), pkg.id(), pkg.customerId(), pkg.storeId(),
                MembershipModels.TYPE_CONSUME, -1, orderId, order.therapistId(),
                requestId, "上课", now));

        advancePlan(pkg.customerId(), now);
    }

    /**
     * 进度由耗课累加，不手填 —— 手填的进度迟早跟实际上课对不上。
     * 只推进"进行中"的那个计划；练满了自动收尾。
     */
    private void advancePlan(long customerId, Instant now) {
        store.listPlansByCustomer(customerId).stream()
                .filter(p -> p.status() == MembershipModels.PLAN_RUNNING)
                .findFirst()
                .ifPresent(p -> {
                    int done = p.doneSessions() + 1;
                    int status = done >= p.totalSessions()
                            ? MembershipModels.PLAN_DONE : p.status();
                    store.upsertPlan(new MembershipModels.Plan(
                            p.id(), p.customerId(), p.storeId(), p.therapistId(), p.title(),
                            p.goal(), p.totalSessions(), done, p.weeklyFrequency(),
                            p.startOn(), p.endOn(), status, p.createdAt(), now));
                });
    }

    // ---------- 查询 ----------

    /** 可用于约课的课包：状态正常、有余次、没过期。 */
    public List<MembershipModels.Package> usablePackages(long customerId) {
        LocalDate today = clock.today();
        return store.listPackagesByCustomer(customerId).stream()
                .filter(p -> p.usableOn(today))
                .toList();
    }

    public List<MembershipModels.Package> allPackages(long customerId) {
        return store.listPackagesByCustomer(customerId);
    }

    public Optional<MembershipModels.Profile> profile(long customerId) {
        return store.findProfile(customerId);
    }

    public List<MembershipModels.Plan> plans(long customerId) {
        return store.listPlansByCustomer(customerId);
    }

    public List<MembershipModels.Profile> members(long storeId, Long ownerTherapistId) {
        return store.listProfiles(storeId, ownerTherapistId);
    }

    /** 累计已完成课时：跨所有课包求和，包含已用完和已过期的。 */
    public int totalDoneSessions(long customerId) {
        return store.listPackagesByCustomer(customerId).stream()
                .mapToInt(MembershipModels.Package::usedSessions)
                .sum();
    }

    public int totalRemainingSessions(long customerId) {
        LocalDate today = clock.today();
        return store.listPackagesByCustomer(customerId).stream()
                .filter(p -> p.usableOn(today))
                .mapToInt(MembershipModels.Package::remainingSessions)
                .sum();
    }

    // ---------- 档案 ----------

    public MembershipModels.Profile ensureProfile(long customerId, long storeId, Long ownerTherapistId) {
        return store.findProfile(customerId).orElseGet(() -> {
            Instant now = Instant.now(clock.clock());
            MembershipModels.Profile fresh = new MembershipModels.Profile(
                    ids.nextId(), customerId, storeId, ownerTherapistId, null, null, now, now);
            store.upsertProfile(fresh);
            return fresh;
        });
    }

    public MembershipModels.Profile saveProfile(
            long customerId, long storeId, Long ownerTherapistId, String coreIssue, String remark) {
        MembershipModels.Profile cur = ensureProfile(customerId, storeId, ownerTherapistId);
        MembershipModels.Profile next = new MembershipModels.Profile(
                cur.id(), cur.customerId(), cur.storeId(),
                ownerTherapistId != null ? ownerTherapistId : cur.ownerTherapistId(),
                coreIssue != null ? coreIssue : cur.coreIssue(),
                remark != null ? remark : cur.remark(),
                cur.createdAt(), Instant.now(clock.clock()));
        store.upsertProfile(next);
        return next;
    }

    private Long packageIdOf(BookingOrderRef order) {
        return orders.memberPackageIdOf(order.id());
    }
}
