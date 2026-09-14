package com.jisuodashi.membership;

import com.jisuodashi.common.ClockConfig;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
@Profile("dev")
public class InMemoryMembershipStore implements MembershipStore {

    private final ConcurrentHashMap<Long, MembershipModels.Profile> profiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, MembershipModels.Package> packages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, MembershipModels.Plan> plans = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<MembershipModels.Txn> txns = new CopyOnWriteArrayList<>();

    @Override
    public Optional<MembershipModels.Profile> findProfile(long customerId) {
        return profiles.values().stream().filter(p -> p.customerId() == customerId).findFirst();
    }

    @Override
    public void upsertProfile(MembershipModels.Profile profile) {
        profiles.put(profile.id(), profile);
    }

    @Override
    public List<MembershipModels.Profile> listProfiles(long storeId, Long ownerTherapistId) {
        return profiles.values().stream()
                .filter(p -> p.storeId() == storeId)
                .filter(p -> ownerTherapistId == null
                        || (p.ownerTherapistId() != null && p.ownerTherapistId().equals(ownerTherapistId)))
                .sorted(Comparator.comparingLong(MembershipModels.Profile::id))
                .toList();
    }

    @Override
    public Optional<MembershipModels.Package> findPackage(long id) {
        return Optional.ofNullable(packages.get(id));
    }

    @Override
    public void insertPackage(MembershipModels.Package pkg) {
        packages.put(pkg.id(), pkg);
    }

    @Override
    public void updatePackage(MembershipModels.Package pkg) {
        packages.put(pkg.id(), pkg);
    }

    @Override
    public List<MembershipModels.Package> listPackagesByCustomer(long customerId) {
        return packages.values().stream()
                .filter(p -> p.customerId() == customerId)
                .sorted(Comparator.comparingLong(MembershipModels.Package::id).reversed())
                .toList();
    }

    @Override
    public List<MembershipModels.Package> listPackagesSold(
            long storeId, Long therapistId, LocalDate from, LocalDate to) {
        return packages.values().stream()
                .filter(p -> p.storeId() == storeId)
                .filter(p -> therapistId == null
                        || (p.sellerTherapistId() != null && p.sellerTherapistId().equals(therapistId)))
                .filter(p -> inRange(day(p.createdAt()), from, to))
                .sorted(Comparator.comparingLong(MembershipModels.Package::id).reversed())
                .toList();
    }

    @Override
    public void insertTxn(MembershipModels.Txn txn) {
        txns.add(txn);
    }

    @Override
    public boolean txnExists(String requestId) {
        return requestId != null && !requestId.isBlank()
                && txns.stream().anyMatch(t -> requestId.equals(t.requestId()));
    }

    @Override
    public List<MembershipModels.Txn> listTxnsByPackage(long memberPackageId) {
        return txns.stream()
                .filter(t -> t.memberPackageId() == memberPackageId)
                .sorted(Comparator.comparing(MembershipModels.Txn::createdAt)
                        .thenComparingLong(MembershipModels.Txn::id).reversed())
                .toList();
    }

    @Override
    public List<MembershipModels.Txn> listConsumed(
            long storeId, Long therapistId, LocalDate from, LocalDate to) {
        return txns.stream()
                .filter(t -> MembershipModels.TYPE_CONSUME.equals(t.type()))
                .filter(t -> t.storeId() == storeId)
                .filter(t -> therapistId == null
                        || (t.therapistId() != null && t.therapistId().equals(therapistId)))
                .filter(t -> inRange(day(t.createdAt()), from, to))
                .sorted(Comparator.comparing(MembershipModels.Txn::createdAt).reversed())
                .toList();
    }

    @Override
    public void upsertPlan(MembershipModels.Plan plan) {
        plans.put(plan.id(), plan);
    }

    @Override
    public Optional<MembershipModels.Plan> findPlan(long id) {
        return Optional.ofNullable(plans.get(id));
    }

    @Override
    public List<MembershipModels.Plan> listPlansByCustomer(long customerId) {
        return plans.values().stream()
                .filter(p -> p.customerId() == customerId)
                // 进行中的排前面：老师打开会员详情，先要看的是现在在练什么。
                .sorted(Comparator.comparingInt((MembershipModels.Plan p) ->
                                p.status() == MembershipModels.PLAN_RUNNING ? 0 : 1)
                        .thenComparing(Comparator.comparingLong(MembershipModels.Plan::id).reversed()))
                .toList();
    }

    private static LocalDate day(java.time.Instant at) {
        return at.atZone(ClockConfig.SHANGHAI).toLocalDate();
    }

    private static boolean inRange(LocalDate d, LocalDate from, LocalDate to) {
        return !d.isBefore(from) && !d.isAfter(to);
    }
}
