package com.jisuodashi.growth;

import com.jisuodashi.common.ClockConfig;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
@Profile("dev")
public class InMemoryGrowthStore implements GrowthStore {

    private final ConcurrentHashMap<Long, GrowthModels.FollowUp> followUps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, GrowthModels.Attendance> attendance = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, GrowthModels.Checkin> checkins = new ConcurrentHashMap<>();
    private final Set<String> likes = ConcurrentHashMap.newKeySet();
    private final CopyOnWriteArrayList<GrowthModels.Assessment> assessments = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<GrowthModels.StageReview> stageReviews = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<Long, GrowthModels.ExternalClaim> claims = new ConcurrentHashMap<>();

    @Override
    public void insertFollowUp(GrowthModels.FollowUp row) {
        followUps.put(row.id(), row);
    }

    @Override
    public void updateFollowUp(GrowthModels.FollowUp row) {
        followUps.put(row.id(), row);
    }

    @Override
    public Optional<GrowthModels.FollowUp> findFollowUp(long id) {
        return Optional.ofNullable(followUps.get(id));
    }

    @Override
    public List<GrowthModels.FollowUp> listFollowUpsByStaff(long staffId, LocalDate from, LocalDate to) {
        return followUps.values().stream()
                .filter(f -> f.staffId() == staffId)
                .filter(f -> inWindow(f, from, to))
                .sorted(byDueThenId())
                .toList();
    }

    @Override
    public List<GrowthModels.FollowUp> listFollowUpsByCustomer(long customerId) {
        return followUps.values().stream()
                .filter(f -> f.customerId() == customerId)
                .sorted(Comparator.comparingLong(GrowthModels.FollowUp::id).reversed())
                .toList();
    }

    @Override
    public List<GrowthModels.FollowUp> listFollowUpsByStore(long storeId, LocalDate from, LocalDate to) {
        return followUps.values().stream()
                .filter(f -> f.storeId() == storeId)
                .filter(f -> inWindow(f, from, to))
                .sorted(byDueThenId())
                .toList();
    }

    /** 待办按应回访日排，做完的按时间倒序 —— 老师打开就该先看到今天要打的电话。 */
    private static Comparator<GrowthModels.FollowUp> byDueThenId() {
        return Comparator.comparingInt((GrowthModels.FollowUp f) -> f.pending() ? 0 : 1)
                .thenComparing(f -> f.dueOn() == null ? LocalDate.MAX : f.dueOn())
                .thenComparingLong(GrowthModels.FollowUp::id);
    }

    private static boolean inWindow(GrowthModels.FollowUp f, LocalDate from, LocalDate to) {
        LocalDate d = f.dueOn() != null ? f.dueOn() : day(f.createdAt());
        return !d.isBefore(from) && !d.isAfter(to);
    }

    @Override
    public Optional<GrowthModels.Attendance> findAttendance(long staffId, LocalDate day) {
        return Optional.ofNullable(attendance.get(staffId + "@" + day));
    }

    @Override
    public void upsertAttendance(GrowthModels.Attendance row) {
        attendance.put(row.staffId() + "@" + row.workDay(), row);
    }

    @Override
    public List<GrowthModels.Attendance> listAttendance(long staffId, LocalDate from, LocalDate to) {
        return attendance.values().stream()
                .filter(a -> a.staffId() == staffId)
                .filter(a -> !a.workDay().isBefore(from) && !a.workDay().isAfter(to))
                .sorted(Comparator.comparing(GrowthModels.Attendance::workDay))
                .toList();
    }

    @Override
    public Optional<GrowthModels.Checkin> findCheckin(long customerId, LocalDate day) {
        return checkins.values().stream()
                .filter(c -> c.customerId() == customerId && c.checkDay().equals(day))
                .findFirst();
    }

    @Override
    public void upsertCheckin(GrowthModels.Checkin row) {
        checkins.put(row.id(), row);
    }

    @Override
    public Optional<GrowthModels.Checkin> findCheckinById(long id) {
        return Optional.ofNullable(checkins.get(id));
    }

    @Override
    public List<GrowthModels.Checkin> listCheckinsByCustomer(long customerId, LocalDate from, LocalDate to) {
        return checkins.values().stream()
                .filter(c -> c.customerId() == customerId)
                .filter(c -> !c.checkDay().isBefore(from) && !c.checkDay().isAfter(to))
                .sorted(Comparator.comparing(GrowthModels.Checkin::checkDay).reversed())
                .toList();
    }

    @Override
    public List<GrowthModels.Checkin> listPublicCheckins(long storeId, int limit) {
        return checkins.values().stream()
                .filter(c -> c.storeId() == storeId && c.isPublic())
                .sorted(Comparator.comparing(GrowthModels.Checkin::checkDay)
                        .thenComparingLong(GrowthModels.Checkin::id).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public boolean like(long checkinId, long customerId) {
        if (!likes.add(checkinId + "@" + customerId)) {
            return false;
        }
        checkins.computeIfPresent(checkinId, (k, c) -> new GrowthModels.Checkin(
                c.id(), c.customerId(), c.storeId(), c.planId(), c.checkDay(),
                c.content(), c.imageUrls(), c.visibility(), c.likeCount() + 1, c.createdAt()));
        return true;
    }

    @Override
    public void insertAssessment(GrowthModels.Assessment row) {
        assessments.add(row);
    }

    @Override
    public List<GrowthModels.Assessment> listAssessments(long customerId) {
        return assessments.stream()
                .filter(a -> a.customerId() == customerId)
                .sorted(Comparator.comparing(GrowthModels.Assessment::assessedOn)
                        .thenComparingLong(GrowthModels.Assessment::id))
                .toList();
    }

    @Override
    public void insertStageReview(GrowthModels.StageReview row) {
        stageReviews.add(row);
    }

    @Override
    public List<GrowthModels.StageReview> listStageReviews(
            Long therapistId, Long customerId, String direction, LocalDate from, LocalDate to) {
        return stageReviews.stream()
                .filter(r -> therapistId == null
                        || (r.therapistId() != null && r.therapistId().equals(therapistId)))
                .filter(r -> customerId == null || r.customerId() == customerId)
                .filter(r -> direction == null || direction.equals(r.direction()))
                .filter(r -> inRange(day(r.createdAt()), from, to))
                .sorted(Comparator.comparing(GrowthModels.StageReview::createdAt).reversed())
                .toList();
    }

    @Override
    public void insertClaim(GrowthModels.ExternalClaim row) {
        claims.put(row.id(), row);
    }

    @Override
    public void updateClaim(GrowthModels.ExternalClaim row) {
        claims.put(row.id(), row);
    }

    @Override
    public Optional<GrowthModels.ExternalClaim> findClaim(long id) {
        return Optional.ofNullable(claims.get(id));
    }

    @Override
    public List<GrowthModels.ExternalClaim> listClaims(
            long storeId, String status, LocalDate from, LocalDate to) {
        return claims.values().stream()
                .filter(c -> c.storeId() == storeId)
                .filter(c -> status == null || status.equals(c.status()))
                .filter(c -> inRange(c.claimedOn(), from, to))
                .sorted(Comparator.comparingLong(GrowthModels.ExternalClaim::id).reversed())
                .toList();
    }

    @Override
    public List<GrowthModels.ExternalClaim> listClaimsByTherapist(
            long therapistId, LocalDate from, LocalDate to) {
        return claims.values().stream()
                .filter(c -> c.therapistId() == therapistId)
                .filter(c -> inRange(c.claimedOn(), from, to))
                .sorted(Comparator.comparingLong(GrowthModels.ExternalClaim::id).reversed())
                .toList();
    }

    private static LocalDate day(Instant at) {
        return at.atZone(ClockConfig.SHANGHAI).toLocalDate();
    }

    private static boolean inRange(LocalDate d, LocalDate from, LocalDate to) {
        return d != null && !d.isBefore(from) && !d.isAfter(to);
    }
}
