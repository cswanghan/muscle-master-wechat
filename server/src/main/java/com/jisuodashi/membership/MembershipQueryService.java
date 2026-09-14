package com.jisuodashi.membership;

import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.auth.Customer;
import com.jisuodashi.auth.CustomerRepository;
import com.jisuodashi.catalog.CatalogModels;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.PhoneCrypto;
import com.jisuodashi.inventory.AvailabilityDtos;
import com.jisuodashi.inventory.AvailabilityService;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import com.jisuodashi.inventory.SlotTimes;
import com.jisuodashi.staff.StaffTherapistLookup;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 老师端的会员视图。只读，负责把课包、计划、订单拼成老师看得懂的一行。
 */
@Service
public class MembershipQueryService {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter MD_HM = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    /** 还会发生的课：这些单才算"下一次上课"。 */
    private static final List<String> UPCOMING = List.of("BOOKED", "CHECKED_IN", "IN_SERVICE");

    private final MembershipService membership;
    private final CustomerRepository customers;
    private final PhoneCrypto phones;
    private final StaffTherapistLookup therapists;
    private final SlotOccupyService occupy;
    private final AvailabilityService availability;
    private final AppClock clock;

    public MembershipQueryService(
            MembershipService membership,
            CustomerRepository customers,
            PhoneCrypto phones,
            StaffTherapistLookup therapists,
            SlotOccupyService occupy,
            AvailabilityService availability,
            AppClock clock) {
        this.membership = membership;
        this.customers = customers;
        this.phones = phones;
        this.therapists = therapists;
        this.occupy = occupy;
        this.availability = availability;
        this.clock = clock;
    }

    /** 我的会员。默认只看归属自己的；{@code scope=store} 看全店。 */
    public MembershipDtos.MemberListResponse myMembers(String scope) {
        CatalogModels.Therapist me = therapists.requireTherapist(AuthContext.requireStaff());
        boolean wholeStore = "store".equalsIgnoreCase(scope);
        List<MembershipModels.Profile> profiles =
                membership.members(me.homeStoreId(), wholeStore ? null : me.id());

        LocalDate today = clock.today();
        // 一次把未来的单捞出来按会员分组，避免每行会员各查一次。
        Map<Long, List<BookingOrderRef>> upcoming = occupy
                .listOrdersByTherapist(me.id(), today, today.plusDays(60)).stream()
                .filter(o -> UPCOMING.contains(o.status()))
                .collect(Collectors.groupingBy(BookingOrderRef::customerId));

        List<MembershipDtos.MemberItem> items = new ArrayList<>();
        for (MembershipModels.Profile p : profiles) {
            MembershipModels.Plan plan = membership.plans(p.customerId()).stream()
                    .filter(x -> x.status() == MembershipModels.PLAN_RUNNING)
                    .findFirst()
                    .orElse(null);
            items.add(new MembershipDtos.MemberItem(
                    String.valueOf(p.customerId()),
                    mask(p.customerId()),
                    p.coreIssue(),
                    membership.totalRemainingSessions(p.customerId()),
                    membership.totalDoneSessions(p.customerId()),
                    plan == null ? null : plan.title(),
                    plan == null ? 0 : plan.doneSessions(),
                    plan == null ? 0 : plan.totalSessions(),
                    plan == null ? 0 : plan.progressX100(),
                    nextLesson(upcoming.get(p.customerId()))));
        }
        // 快没课的排前面 —— 那是该提醒续课的人，也是老师最该先看到的。
        items.sort(Comparator.comparingInt(MembershipDtos.MemberItem::remainingSessions));
        return new MembershipDtos.MemberListResponse(items, items.size());
    }

    /**
     * 当日时间列表。忙的时段也一并给出来 —— 老师要看的是"我这一天长什么样"，
     * 只给空档等于把自己的课表藏起来了。
     */
    public MembershipDtos.DaySlotsResponse daySlots(String dateRaw, String projectIdRaw) {
        CatalogModels.Therapist me = therapists.requireTherapist(AuthContext.requireStaff());
        LocalDate date = parseDate(dateRaw, clock.today());
        long projectId = projectIdRaw == null || projectIdRaw.isBlank()
                ? defaultProjectId(me)
                : parseId(projectIdRaw);

        AvailabilityDtos.Availability av = availability.query(
                me.homeStoreId(), date, projectId, me.id(), true);
        AvailabilityDtos.Therapist mine = av.therapists().stream()
                .filter(x -> String.valueOf(me.id()).equals(x.therapistId()))
                .findFirst()
                .orElse(null);
        if (mine == null) {
            return new MembershipDtos.DaySlotsResponse(
                    date.toString(), String.valueOf(me.id()), me.name(), 0, 0, List.of());
        }

        Map<Integer, AvailabilityDtos.Start> startable = mine.starts() == null ? Map.of()
                : mine.starts().stream().collect(Collectors.toMap(
                        AvailabilityDtos.Start::slotNo, s -> s, (a, b) -> a));
        // 这天谁的单占在哪个时段，顺手带上客户名，老师一眼知道下一位是谁。
        Map<Integer, BookingOrderRef> booked = occupy
                .listOrdersByTherapist(me.id(), date, date).stream()
                .filter(o -> UPCOMING.contains(o.status()))
                .collect(Collectors.toMap(BookingOrderRef::startSlotNo, o -> o, (a, b) -> a));

        List<MembershipDtos.SlotItem> slots = new ArrayList<>();
        int free = 0;
        int busy = 0;
        for (AvailabilityDtos.Block b : mine.blocks() == null ? List.<AvailabilityDtos.Block>of() : mine.blocks()) {
            boolean bookable = startable.containsKey(b.slotNo());
            BookingOrderRef o = booked.get(b.slotNo());
            if (bookable) {
                free++;
            } else if (o != null) {
                busy++;
            }
            slots.add(new MembershipDtos.SlotItem(
                    b.slotNo(), b.start(), b.state(), bookable,
                    o == null ? null : mask(o.customerId()),
                    o == null ? null : String.valueOf(o.id())));
        }
        return new MembershipDtos.DaySlotsResponse(
                date.toString(), String.valueOf(me.id()), me.name(), free, busy, slots);
    }

    /** 没指定课程时用这位老师会做的第一个项目；可约时段本来就按项目时长算。 */
    private long defaultProjectId(CatalogModels.Therapist me) {
        if (me.projectIds() == null || me.projectIds().isEmpty()) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "该老师未配置可做项目");
        }
        return me.projectIds().getFirst();
    }

    private static LocalDate parseDate(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "date 格式应为 YYYY-MM-DD");
        }
    }

    public MembershipDtos.MemberDetail detail(String customerIdRaw) {
        long customerId = parseId(customerIdRaw);
        LocalDate today = clock.today();
        MembershipModels.Profile p = membership.profile(customerId)
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "会员档案不存在"));

        List<MembershipDtos.PackageItem> packages = membership.allPackages(customerId).stream()
                .map(x -> new MembershipDtos.PackageItem(
                        String.valueOf(x.id()), x.title(),
                        x.totalSessions(), x.usedSessions(), x.remainingSessions(),
                        yuan(x.unitPriceFen()),
                        x.expireOn() == null ? null : x.expireOn().toString(),
                        x.usableOn(today),
                        packageStatus(x, today)))
                .toList();

        List<MembershipDtos.PlanItem> plans = membership.plans(customerId).stream()
                .map(MembershipQueryService::toPlanItem)
                .toList();

        return new MembershipDtos.MemberDetail(
                String.valueOf(customerId), mask(customerId), p.coreIssue(), p.remark(),
                p.ownerTherapistId() == null ? null : String.valueOf(p.ownerTherapistId()),
                membership.totalRemainingSessions(customerId),
                membership.totalDoneSessions(customerId),
                packages, plans);
    }

    static MembershipDtos.PlanItem toPlanItem(MembershipModels.Plan x) {
        return new MembershipDtos.PlanItem(
                String.valueOf(x.id()), x.title(), x.goal(),
                x.totalSessions(), x.doneSessions(), x.progressX100(), x.weeklyFrequency(),
                x.startOn().toString(), x.endOn() == null ? null : x.endOn().toString(),
                switch (x.status()) {
                    case MembershipModels.PLAN_DONE -> "已完成";
                    case MembershipModels.PLAN_STOPPED -> "已终止";
                    default -> "进行中";
                });
    }

    private static String packageStatus(MembershipModels.Package x, LocalDate today) {
        if (x.status() == MembershipModels.STATUS_DISABLED) {
            return "已停用";
        }
        if (x.expiredOn(today)) {
            return "已过期";
        }
        if (x.remainingSessions() <= 0) {
            return "已用完";
        }
        return "可用";
    }

    private String nextLesson(List<BookingOrderRef> orders) {
        if (orders == null || orders.isEmpty()) {
            return null;
        }
        return orders.stream()
                .min(Comparator.comparing(BookingOrderRef::serviceDate)
                        .thenComparingInt(BookingOrderRef::startSlotNo))
                .map(o -> o.serviceDate().atTime(SlotTimes.toTime(o.startSlotNo())).format(MD_HM))
                .orElse(null);
    }

    private String mask(long customerId) {
        return customers.findById(customerId)
                .map(this::maskOf)
                .orElse("****");
    }

    private String maskOf(Customer c) {
        if (c.getPhoneCipher() != null && c.getPhoneCipher().length > 0) {
            return PhoneCrypto.mask(phones.decrypt(c.getPhoneCipher()));
        }
        return "****";
    }

    static long parseId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "id 无效");
        }
    }

    static String yuan(long fen) {
        return String.format("%.2f", fen / 100.0);
    }
}
