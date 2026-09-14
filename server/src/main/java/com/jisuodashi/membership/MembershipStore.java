package com.jisuodashi.membership;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MembershipStore {

    // --- 档案 ---

    Optional<MembershipModels.Profile> findProfile(long customerId);

    void upsertProfile(MembershipModels.Profile profile);

    /** 某老师名下的会员。ownerTherapistId 为空时返回门店全部会员。 */
    List<MembershipModels.Profile> listProfiles(long storeId, Long ownerTherapistId);

    // --- 课包 ---

    Optional<MembershipModels.Package> findPackage(long id);

    void insertPackage(MembershipModels.Package pkg);

    void updatePackage(MembershipModels.Package pkg);

    List<MembershipModels.Package> listPackagesByCustomer(long customerId);

    /** 卖课业绩用：某段时间内某门店售出的课包。therapistId 为空则不按卖课人过滤。 */
    List<MembershipModels.Package> listPackagesSold(long storeId, Long therapistId, LocalDate from, LocalDate to);

    // --- 流水 ---

    void insertTxn(MembershipModels.Txn txn);

    boolean txnExists(String requestId);

    List<MembershipModels.Txn> listTxnsByPackage(long memberPackageId);

    /** 耗课业绩用。therapistId 为空则统计整店。 */
    List<MembershipModels.Txn> listConsumed(long storeId, Long therapistId, LocalDate from, LocalDate to);

    // --- 训练计划 ---

    void upsertPlan(MembershipModels.Plan plan);

    Optional<MembershipModels.Plan> findPlan(long id);

    /** 一位会员的计划，进行中的排在前。 */
    List<MembershipModels.Plan> listPlansByCustomer(long customerId);
}
