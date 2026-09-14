package com.jisuodashi.growth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.auth.Customer;
import com.jisuodashi.auth.CustomerRepository;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.catalog.CatalogModels;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.PhoneCrypto;
import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.membership.MembershipModels;
import com.jisuodashi.membership.MembershipService;
import com.jisuodashi.staff.StaffTherapistLookup;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 回访、考勤、打卡、评估、双向打分、外部好评认领。 */
@Service
public class GrowthService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter YMD_HM = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final GrowthStore store;
    private final MembershipService membership;
    private final CustomerRepository customers;
    private final PhoneCrypto phones;
    private final StaffTherapistLookup therapists;
    private final SnowflakeIdGenerator ids;
    private final AppClock clock;

    public GrowthService(
            GrowthStore store,
            MembershipService membership,
            CustomerRepository customers,
            PhoneCrypto phones,
            StaffTherapistLookup therapists,
            SnowflakeIdGenerator ids,
            AppClock clock) {
        this.store = store;
        this.membership = membership;
        this.customers = customers;
        this.phones = phones;
        this.therapists = therapists;
        this.ids = ids;
        this.clock = clock;
    }

    // ─────────── 回访 ───────────

    public GrowthDtos.FollowUpListResponse myFollowUps(String fromRaw, String toRaw) {
        JwtPrincipal me = AuthContext.requireStaff();
        LocalDate today = clock.today();
        LocalDate from = parseDate(fromRaw, today.withDayOfMonth(1));
        LocalDate to = parseDate(toRaw, today);
        List<GrowthModels.FollowUp> rows = store.listFollowUpsByStaff(me.staffId(), from, to);
        return toFollowUpList(rows, today);
    }

    private GrowthDtos.FollowUpListResponse toFollowUpList(
            List<GrowthModels.FollowUp> rows, LocalDate today) {
        List<GrowthDtos.FollowUpItem> items = rows.stream().map(f -> new GrowthDtos.FollowUpItem(
                String.valueOf(f.id()),
                String.valueOf(f.customerId()),
                mask(f.customerId()),
                nickname(f.customerId()),
                f.kind(), kindLabel(f.kind()), f.channel(), f.content(), f.outcome(),
                f.dueOn() == null ? null : f.dueOn().toString(),
                f.doneAt() == null ? null : YMD_HM.format(f.doneAt().atZone(clock.clock().getZone())),
                f.pending(), f.overdueOn(today))).toList();
        int pending = (int) rows.stream().filter(GrowthModels.FollowUp::pending).count();
        int overdue = (int) rows.stream().filter(f -> f.overdueOn(today)).count();
        // 完成度只看"到期该做的"，还没到期的不算分母，否则提前建的待办会拉低分数。
        long due = rows.stream().filter(f -> f.dueOn() == null || !f.dueOn().isAfter(today)).count();
        long done = rows.stream()
                .filter(f -> f.dueOn() == null || !f.dueOn().isAfter(today))
                .filter(f -> !f.pending()).count();
        return new GrowthDtos.FollowUpListResponse(
                items, pending, overdue, GrowthPolicy.rateX100(done, due));
    }

    public GrowthDtos.FollowUpItem logFollowUp(GrowthDtos.FollowUpRequest req) {
        JwtPrincipal me = AuthContext.requireStaff();
        long customerId = parseId(req.customerId());
        long storeId = membership.profile(customerId)
                .map(MembershipModels.Profile::storeId)
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "会员档案不存在"));
        Instant now = Instant.now(clock.clock());
        GrowthModels.FollowUp row = new GrowthModels.FollowUp(
                ids.nextId(), customerId, storeId, me.staffId(),
                roleKindOf(me), blankTo(req.kind(), GrowthModels.FU_AFTER_CLASS),
                blankTo(req.channel(), "WECOM"),
                req.content(), req.outcome(),
                parseDate(req.dueOn(), clock.today()),
                // 带了内容就是已经打完了；只排期不填内容的是待办。
                req.content() == null || req.content().isBlank() ? null : now,
                now);
        store.insertFollowUp(row);
        return toFollowUpList(List.of(row), clock.today()).items().getFirst();
    }

    /** 把一条待办标成已完成。 */
    public GrowthDtos.FollowUpItem completeFollowUp(String idRaw, GrowthDtos.FollowUpRequest req) {
        GrowthModels.FollowUp cur = store.findFollowUp(parseId(idRaw))
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "回访记录不存在"));
        GrowthModels.FollowUp next = new GrowthModels.FollowUp(
                cur.id(), cur.customerId(), cur.storeId(), cur.staffId(), cur.roleKind(),
                cur.kind(), req.channel() == null ? cur.channel() : req.channel(),
                req.content() == null ? cur.content() : req.content(),
                req.outcome() == null ? cur.outcome() : req.outcome(),
                cur.dueOn(), Instant.now(clock.clock()), cur.createdAt());
        store.updateFollowUp(next);
        return toFollowUpList(List.of(next), clock.today()).items().getFirst();
    }

    public List<GrowthDtos.FollowUpItem> customerFollowUps(long customerId) {
        return toFollowUpList(store.listFollowUpsByCustomer(customerId), clock.today()).items();
    }

    /** 课后自动排一条回访待办：上完课第二天该打电话，这是成交路径里写死的一步。 */
    public void scheduleAfterClass(long customerId, long storeId, long staffId) {
        Instant now = Instant.now(clock.clock());
        store.insertFollowUp(new GrowthModels.FollowUp(
                ids.nextId(), customerId, storeId, staffId,
                GrowthModels.ROLE_THERAPIST, GrowthModels.FU_AFTER_CLASS, "WECOM",
                null, null,
                clock.today().plusDays(GrowthPolicy.AFTER_CLASS_DUE_DAYS), null, now));
    }

    // ─────────── 考勤 ───────────

    public GrowthDtos.AttendanceResponse clock(boolean in) {
        JwtPrincipal me = AuthContext.requireStaff();
        LocalDate today = clock.today();
        Instant now = Instant.now(clock.clock());
        long storeId = me.storeIds().isEmpty() ? 0L : me.storeIds().getFirst();
        GrowthModels.Attendance cur = store.findAttendance(me.staffId(), today).orElse(null);
        GrowthModels.Attendance next;
        if (cur == null) {
            next = new GrowthModels.Attendance(
                    ids.nextId(), me.staffId(), storeId, today,
                    in ? now : null, in ? null : now, now, now);
        } else {
            // 上班卡只认第一次：重复点不该把打卡时间往后推，那等于帮人改迟到记录。
            next = new GrowthModels.Attendance(
                    cur.id(), cur.staffId(), cur.storeId(), cur.workDay(),
                    in ? (cur.clockInAt() == null ? now : cur.clockInAt()) : cur.clockInAt(),
                    in ? cur.clockOutAt() : now,
                    cur.createdAt(), now);
        }
        store.upsertAttendance(next);
        return attendanceOf(me.staffId(), today, next);
    }

    public GrowthDtos.AttendanceResponse myAttendance() {
        JwtPrincipal me = AuthContext.requireStaff();
        LocalDate today = clock.today();
        return attendanceOf(me.staffId(), today,
                store.findAttendance(me.staffId(), today).orElse(null));
    }

    private GrowthDtos.AttendanceResponse attendanceOf(
            long staffId, LocalDate day, GrowthModels.Attendance row) {
        int present = presentDays(staffId, day.withDayOfMonth(1), day);
        return new GrowthDtos.AttendanceResponse(
                day.toString(),
                row == null || row.clockInAt() == null ? null : hm(row.clockInAt()),
                row == null || row.clockOutAt() == null ? null : hm(row.clockOutAt()),
                present,
                row != null && row.clockInAt() != null && row.clockOutAt() == null);
    }

    public int presentDays(long staffId, LocalDate from, LocalDate to) {
        return (int) store.listAttendance(staffId, from, to).stream()
                .filter(GrowthModels.Attendance::present).count();
    }

    // ─────────── 课后打卡 ───────────

    public GrowthDtos.CheckinItem checkin(long customerId, GrowthDtos.CheckinRequest req) {
        MembershipModels.Profile p = membership.profile(customerId)
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "会员档案不存在"));
        LocalDate day = parseDate(req.checkDay(), clock.today());
        Instant now = Instant.now(clock.clock());
        // 一人一天一条：重复提交是改今天这条，不是再加一条。
        GrowthModels.Checkin cur = store.findCheckin(customerId, day).orElse(null);
        GrowthModels.Checkin row = new GrowthModels.Checkin(
                cur == null ? ids.nextId() : cur.id(),
                customerId, p.storeId(),
                req.planId() == null || req.planId().isBlank() ? null : parseId(req.planId()),
                day, req.content(), req.imageUrls(),
                blankTo(req.visibility(), GrowthModels.VISIBILITY_PUBLIC),
                cur == null ? 0 : cur.likeCount(),
                cur == null ? now : cur.createdAt());
        store.upsertCheckin(row);
        return toCheckin(row, customerId);
    }

    public GrowthDtos.CheckinFeedResponse feed(long viewerId, String scope) {
        MembershipModels.Profile p = membership.profile(viewerId).orElse(null);
        if (p == null) {
            return new GrowthDtos.CheckinFeedResponse(List.of());
        }
        List<GrowthModels.Checkin> rows = "mine".equalsIgnoreCase(scope)
                ? store.listCheckinsByCustomer(viewerId, clock.today().minusDays(90), clock.today())
                : store.listPublicCheckins(p.storeId(), 50);
        return new GrowthDtos.CheckinFeedResponse(
                rows.stream().map(c -> toCheckin(c, viewerId)).toList());
    }

    public GrowthDtos.CheckinItem like(long viewerId, String checkinIdRaw) {
        long id = parseId(checkinIdRaw);
        store.like(id, viewerId);
        return store.findCheckinById(id)
                .map(c -> toCheckin(c, viewerId))
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "打卡不存在"));
    }

    private GrowthDtos.CheckinItem toCheckin(GrowthModels.Checkin c, long viewerId) {
        List<String> imgs = c.imageUrls() == null || c.imageUrls().isBlank()
                ? List.of() : List.of(c.imageUrls().split(","));
        return new GrowthDtos.CheckinItem(
                String.valueOf(c.id()), String.valueOf(c.customerId()),
                mask(c.customerId()), c.checkDay().toString(), c.content(),
                imgs, c.likeCount(), c.customerId() == viewerId);
    }

    /** 计划配合度：从计划开始到今天，应打卡天数里实际打了几天。 */
    public int complianceX100(long customerId, MembershipModels.Plan plan) {
        if (plan == null) {
            return 0;
        }
        LocalDate today = clock.today();
        LocalDate from = plan.startOn();
        LocalDate to = today.isBefore(from) ? from : today;
        long expected = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        long done = store.listCheckinsByCustomer(customerId, from, to).size();
        return GrowthPolicy.rateX100(done, expected);
    }

    // ─────────── 评估与结果比对 ───────────

    public GrowthDtos.AssessmentResponse assessments(long customerId) {
        List<GrowthModels.Assessment> rows = store.listAssessments(customerId);
        List<GrowthDtos.AssessmentItem> history = rows.stream()
                .map(a -> new GrowthDtos.AssessmentItem(
                        String.valueOf(a.id()), a.phase(), phaseLabel(a.phase()),
                        a.assessedOn().toString(), a.summary(), parseItems(a.itemsJson())))
                .toList();
        return new GrowthDtos.AssessmentResponse(history, compare(rows));
    }

    /**
     * 结果比对：拿 BASELINE 与最新一次并排，按指标名对齐。
     *
     * <p>只有基线才有比对的意义 —— 没做过初次评估的会员，这里返回空而不是拿
     * 第一条凑数，否则"改善了多少"是假的。
     */
    private List<GrowthDtos.AssessCompareRow> compare(List<GrowthModels.Assessment> rows) {
        GrowthModels.Assessment base = rows.stream()
                .filter(a -> GrowthModels.PHASE_BASELINE.equals(a.phase()))
                .findFirst().orElse(null);
        GrowthModels.Assessment last = rows.isEmpty() ? null : rows.getLast();
        if (base == null || last == null || base.id() == last.id()) {
            return List.of();
        }
        Map<String, GrowthDtos.AssessItem> baseMap = new LinkedHashMap<>();
        parseItems(base.itemsJson()).forEach(i -> baseMap.put(i.name(), i));
        List<GrowthDtos.AssessCompareRow> out = new ArrayList<>();
        for (GrowthDtos.AssessItem now : parseItems(last.itemsJson())) {
            GrowthDtos.AssessItem b = baseMap.get(now.name());
            out.add(new GrowthDtos.AssessCompareRow(
                    now.name(), now.unit(),
                    b == null ? null : b.value(), now.value(),
                    delta(b == null ? null : b.value(), now.value())));
        }
        return out;
    }

    /** 数值项给出带符号的差；非数值项（如"疼痛描述"）不硬算。 */
    private static String delta(String from, String to) {
        try {
            double d = Double.parseDouble(to) - Double.parseDouble(from);
            if (Math.abs(d) < 1e-9) {
                return "0";
            }
            String s = d == Math.rint(d)
                    ? String.valueOf((long) d) : String.format("%.1f", d);
            return d > 0 ? "+" + s : s;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public GrowthDtos.AssessmentResponse saveAssessment(
            long customerId, GrowthDtos.AssessmentRequest req) {
        MembershipModels.Profile p = membership.profile(customerId)
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "会员档案不存在"));
        CatalogModels.Therapist me = therapists.requireTherapist(AuthContext.requireStaff());
        store.insertAssessment(new GrowthModels.Assessment(
                ids.nextId(), customerId, p.storeId(), me.id(),
                req.planId() == null || req.planId().isBlank() ? null : parseId(req.planId()),
                blankTo(req.phase(), GrowthModels.PHASE_BASELINE),
                parseDate(req.assessedOn(), clock.today()),
                req.summary(), writeItems(req.items()),
                Instant.now(clock.clock())));
        return assessments(customerId);
    }

    private static List<GrowthDtos.AssessItem> parseItems(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.of(JSON.readValue(json, GrowthDtos.AssessItem[].class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String writeItems(List<GrowthDtos.AssessItem> items) {
        try {
            return JSON.writeValueAsString(items == null ? List.of() : items);
        } catch (Exception e) {
            return "[]";
        }
    }

    // ─────────── 双向打分 ───────────

    /** 客户给老师打阶段分。 */
    public GrowthDtos.StageReviewItem rateTherapist(long customerId, GrowthDtos.StageReviewRequest req) {
        MembershipModels.Profile p = membership.profile(customerId)
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "会员档案不存在"));
        if (req.score() < 1 || req.score() > 5) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "评分应为 1–5");
        }
        Long tid = req.therapistId() == null || req.therapistId().isBlank()
                ? p.ownerTherapistId() : Long.valueOf(parseId(req.therapistId()));
        GrowthModels.StageReview row = new GrowthModels.StageReview(
                ids.nextId(), customerId, p.storeId(), tid,
                req.planId() == null || req.planId().isBlank() ? null : parseId(req.planId()),
                GrowthModels.DIR_C2T, req.score(), req.comment(), Instant.now(clock.clock()));
        store.insertStageReview(row);
        return toStageReview(row);
    }

    /** 系统给客户打配合度。由打卡率换算，不是人填的。 */
    public GrowthDtos.StageReviewItem rateCompliance(long customerId, Long planId) {
        MembershipModels.Profile p = membership.profile(customerId)
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "会员档案不存在"));
        MembershipModels.Plan plan = membership.plans(customerId).stream()
                .filter(x -> planId == null || x.id() == planId)
                .findFirst().orElse(null);
        int pct = complianceX100(customerId, plan);
        int score = GrowthPolicy.complianceScore(pct, 10_000);
        GrowthModels.StageReview row = new GrowthModels.StageReview(
                ids.nextId(), customerId, p.storeId(), p.ownerTherapistId(),
                plan == null ? null : plan.id(), GrowthModels.DIR_SYS2C, score,
                "打卡完成 " + (pct / 100) + "%", Instant.now(clock.clock()));
        store.insertStageReview(row);
        return toStageReview(row);
    }

    private GrowthDtos.StageReviewItem toStageReview(GrowthModels.StageReview r) {
        return new GrowthDtos.StageReviewItem(
                String.valueOf(r.id()), r.direction(), r.score(), r.commentText(),
                mask(r.customerId()), YMD_HM.format(r.createdAt().atZone(clock.clock().getZone())));
    }

    public List<GrowthDtos.StageReviewItem> stageReviewsOfTherapist(
            long therapistId, LocalDate from, LocalDate to) {
        return store.listStageReviews(therapistId, null, GrowthModels.DIR_C2T, from, to)
                .stream().map(this::toStageReview).toList();
    }

    // ─────────── 外部好评认领 ───────────

    public GrowthDtos.ClaimItem claim(GrowthDtos.ClaimRequest req) {
        CatalogModels.Therapist me = therapists.requireTherapist(AuthContext.requireStaff());
        if (req.rating() < 1 || req.rating() > 5) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "评分应为 1–5");
        }
        Instant now = Instant.now(clock.clock());
        GrowthModels.ExternalClaim row = new GrowthModels.ExternalClaim(
                ids.nextId(), me.homeStoreId(), me.id(),
                req.customerId() == null || req.customerId().isBlank()
                        ? null : parseId(req.customerId()),
                blankTo(req.platform(), "DIANPING"), req.rating(), req.proofUrl(),
                parseDate(req.claimedOn(), clock.today()),
                GrowthModels.CLAIM_PENDING, null, now, now);
        store.insertClaim(row);
        return toClaim(row);
    }

    public GrowthDtos.ClaimListResponse listClaims(long storeId, String status, LocalDate from, LocalDate to) {
        List<GrowthModels.ExternalClaim> rows = store.listClaims(storeId, status, from, to);
        return new GrowthDtos.ClaimListResponse(
                rows.stream().map(this::toClaim).toList(),
                (int) rows.stream().filter(c -> GrowthModels.CLAIM_PENDING.equals(c.status())).count(),
                (int) rows.stream().filter(GrowthModels.ExternalClaim::approved).count());
    }

    public GrowthDtos.ClaimItem decideClaim(String idRaw, boolean approve) {
        JwtPrincipal me = AuthContext.requireStaff();
        GrowthModels.ExternalClaim cur = store.findClaim(parseId(idRaw))
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "认领不存在"));
        if (!GrowthModels.CLAIM_PENDING.equals(cur.status())) {
            throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "该认领已处理");
        }
        GrowthModels.ExternalClaim next = new GrowthModels.ExternalClaim(
                cur.id(), cur.storeId(), cur.therapistId(), cur.customerId(),
                cur.platform(), cur.rating(), cur.proofUrl(), cur.claimedOn(),
                approve ? GrowthModels.CLAIM_APPROVED : GrowthModels.CLAIM_REJECTED,
                me.staffId(), cur.createdAt(), Instant.now(clock.clock()));
        store.updateClaim(next);
        return toClaim(next);
    }

    /** 外部好评：只算审核通过的，否则认领一张截图就能刷好评率。 */
    public List<GrowthModels.ExternalClaim> approvedClaims(
            long therapistId, LocalDate from, LocalDate to) {
        return store.listClaimsByTherapist(therapistId, from, to).stream()
                .filter(GrowthModels.ExternalClaim::approved)
                .toList();
    }

    private GrowthDtos.ClaimItem toClaim(GrowthModels.ExternalClaim c) {
        return new GrowthDtos.ClaimItem(
                String.valueOf(c.id()), c.platform(), platformLabel(c.platform()), c.rating(),
                String.valueOf(c.therapistId()),
                therapistName(c.therapistId()), c.proofUrl(),
                c.claimedOn().toString(), c.status(), claimStatusLabel(c.status()));
    }

    // ─────────── 公共 ───────────

    public String mask(long customerId) {
        return customers.findById(customerId).map(this::maskOf).orElse("****");
    }

    private String maskOf(Customer c) {
        if (c.getPhoneCipher() != null && c.getPhoneCipher().length > 0) {
            return PhoneCrypto.mask(phones.decrypt(c.getPhoneCipher()));
        }
        return "****";
    }

    private String nickname(long customerId) {
        return membership.profile(customerId)
                .map(MembershipModels.Profile::wxNickname).orElse(null);
    }

    private String therapistName(long therapistId) {
        return therapists.nameOf(therapistId);
    }

    static String kindLabel(String k) {
        return switch (k == null ? "" : k) {
            case GrowthModels.FU_AFTER_CLASS -> "课后回访";
            case GrowthModels.FU_RETENTION -> "续费回访";
            case GrowthModels.FU_REVIVE -> "唤回";
            case GrowthModels.FU_TRIAL -> "体验后回访";
            default -> k;
        };
    }

    static String phaseLabel(String p) {
        return switch (p == null ? "" : p) {
            case GrowthModels.PHASE_BASELINE -> "初次评估";
            case GrowthModels.PHASE_MID -> "阶段中";
            case GrowthModels.PHASE_FINAL -> "阶段末";
            default -> p;
        };
    }

    static String platformLabel(String p) {
        return switch (p == null ? "" : p) {
            case "DIANPING" -> "大众点评";
            case "XIAOHONGSHU" -> "小红书";
            case "DOUYIN" -> "抖音";
            case "MEITUAN" -> "美团";
            default -> p;
        };
    }

    static String claimStatusLabel(String s) {
        return switch (s == null ? "" : s) {
            case GrowthModels.CLAIM_APPROVED -> "已通过";
            case GrowthModels.CLAIM_REJECTED -> "已驳回";
            default -> "待审核";
        };
    }

    private static String roleKindOf(JwtPrincipal me) {
        return me.typ() == com.jisuodashi.auth.TokenType.T
                ? GrowthModels.ROLE_THERAPIST : GrowthModels.ROLE_MANAGER;
    }

    private String hm(Instant at) {
        return HM.format(at.atZone(clock.clock().getZone()));
    }

    static String blankTo(String raw, String fallback) {
        return raw == null || raw.isBlank() ? fallback : raw.trim();
    }

    public static LocalDate parseDate(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "日期格式应为 YYYY-MM-DD");
        }
    }

    public static long parseId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "id 无效");
        }
    }
}
