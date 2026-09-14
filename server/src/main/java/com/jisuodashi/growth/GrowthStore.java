package com.jisuodashi.growth;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface GrowthStore {

    // 回访
    void insertFollowUp(GrowthModels.FollowUp row);

    void updateFollowUp(GrowthModels.FollowUp row);

    Optional<GrowthModels.FollowUp> findFollowUp(long id);

    List<GrowthModels.FollowUp> listFollowUpsByStaff(long staffId, LocalDate from, LocalDate to);

    List<GrowthModels.FollowUp> listFollowUpsByCustomer(long customerId);

    List<GrowthModels.FollowUp> listFollowUpsByStore(long storeId, LocalDate from, LocalDate to);

    // 考勤
    Optional<GrowthModels.Attendance> findAttendance(long staffId, LocalDate day);

    void upsertAttendance(GrowthModels.Attendance row);

    List<GrowthModels.Attendance> listAttendance(long staffId, LocalDate from, LocalDate to);

    // 课后打卡
    Optional<GrowthModels.Checkin> findCheckin(long customerId, LocalDate day);

    void upsertCheckin(GrowthModels.Checkin row);

    Optional<GrowthModels.Checkin> findCheckinById(long id);

    List<GrowthModels.Checkin> listCheckinsByCustomer(long customerId, LocalDate from, LocalDate to);

    /** 门店广场：只出 PUBLIC。 */
    List<GrowthModels.Checkin> listPublicCheckins(long storeId, int limit);

    /** 点赞去重靠主键；返回 false 表示已赞过。 */
    boolean like(long checkinId, long customerId);

    // 评估
    void insertAssessment(GrowthModels.Assessment row);

    List<GrowthModels.Assessment> listAssessments(long customerId);

    // 双向打分
    void insertStageReview(GrowthModels.StageReview row);

    List<GrowthModels.StageReview> listStageReviews(Long therapistId, Long customerId, String direction,
                                                    LocalDate from, LocalDate to);

    // 外部好评认领
    void insertClaim(GrowthModels.ExternalClaim row);

    void updateClaim(GrowthModels.ExternalClaim row);

    Optional<GrowthModels.ExternalClaim> findClaim(long id);

    List<GrowthModels.ExternalClaim> listClaims(long storeId, String status, LocalDate from, LocalDate to);

    List<GrowthModels.ExternalClaim> listClaimsByTherapist(long therapistId, LocalDate from, LocalDate to);
}
