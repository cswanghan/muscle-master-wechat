package com.jisuodashi.performance;

import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.catalog.CatalogModels;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import com.jisuodashi.inventory.SlotTimes;
import com.jisuodashi.staff.StaffTherapistLookup;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 技师自己的业绩，按日 / 周 / 月。只读，不落账 —— 真正的工资结算是财务侧的事，
 * 这里给的是技师自查口径。
 */
@Service
public class PerformanceService {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    /** 计提成的状态：服务已发生。已取消 / 未支付不算。 */
    private static final List<String> EARNING = List.of("COMPLETED", "IN_SERVICE", "CHECKED_IN");
    /** 退款回滚：钱退了，提成要扣回来。 */
    private static final List<String> ROLLBACK = List.of("CANCELLED");

    private final SlotOccupyService occupy;
    private final StaffTherapistLookup therapists;
    private final AppClock clock;

    public PerformanceService(
            SlotOccupyService occupy, StaffTherapistLookup therapists, AppClock clock) {
        this.occupy = occupy;
        this.therapists = therapists;
        this.clock = clock;
    }

    public PerformanceDtos.Summary mine(String rangeRaw) {
        CatalogModels.Therapist me = therapists.requireTherapist(AuthContext.requireStaff());
        String range = normalize(rangeRaw);
        LocalDate today = clock.today();
        LocalDate from = startOf(range, today);

        List<BookingOrderRef> orders = occupy.listOrdersByTherapist(me.id(), from, today);
        List<PerformanceDtos.Entry> entries = new ArrayList<>();
        long service = 0;
        long addOn = 0;
        long designated = 0;
        long refund = 0;
        int clocks = 0;

        for (BookingOrderRef o : orders) {
            boolean earning = EARNING.contains(o.status());
            boolean rolledBack = ROLLBACK.contains(o.status());
            if (!earning && !rolledBack) {
                continue;
            }
            long commission = CommissionPolicy.commissionFen(o.payableFen(), me.level());
            if (rolledBack) {
                // 负数入账，前端按红色渲染；不从 total 里"藏掉"这笔。
                refund += commission;
                entries.add(entry(o, "REFUND", -o.payableFen(), -commission, false));
                continue;
            }
            clocks++;
            service += commission;
            entries.add(entry(o, "SERVICE", o.payableFen(), commission, o.designated()));
            if (o.designated()) {
                // 点名加成是定额，不随客单价浮动 —— 认的是"冲这个人来的"，不是单子大小。
                designated += CommissionPolicy.DESIGNATED_BONUS_FEN;
                entries.add(entry(o, "DESIGNATED", 0L, CommissionPolicy.DESIGNATED_BONUS_FEN, true));
            }
            if (o.addOnHoldId() != null) {
                // 加钟单独成条：技师看的是"这一单我拿了多少"，混在一起就说不清。
                long addOnCommission = CommissionPolicy.commissionFen(o.payableFen(), me.level());
                addOn += addOnCommission;
                entries.add(entry(o, "ADD_ON", o.payableFen(), addOnCommission, false));
            }
        }

        long total = service + addOn + designated - refund;
        return new PerformanceDtos.Summary(
                range,
                from.toString(),
                today.toString(),
                me.level(),
                CommissionPolicy.rateX100(me.level()),
                clocks,
                total,
                new PerformanceDtos.Breakdown(service, addOn, designated, refund, 0L),
                entries);
    }

    private static PerformanceDtos.Entry entry(
            BookingOrderRef o, String kind, long amountFen, long commissionFen, boolean designated) {
        return new PerformanceDtos.Entry(
                String.valueOf(o.id()),
                o.orderNo(),
                kind,
                titleOf(kind),
                o.serviceDate().toString(),
                SlotTimes.toTime(o.startSlotNo()).format(HM),
                amountFen,
                commissionFen,
                designated);
    }

    private static String titleOf(String kind) {
        return switch (kind) {
            case "ADD_ON" -> "加钟";
            case "REFUND" -> "退款回滚";
            case "DESIGNATED" -> "指定加成";
            default -> "到店服务";
        };
    }

    private static String normalize(String raw) {
        String r = raw == null || raw.isBlank() ? "month" : raw.trim().toLowerCase(Locale.ROOT);
        if (!List.of("day", "week", "month").contains(r)) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "range 仅支持 day / week / month");
        }
        return r;
    }

    /** 周一为周首、当月 1 号为月首，与运营口径一致。 */
    private static LocalDate startOf(String range, LocalDate today) {
        return switch (range) {
            case "day" -> today;
            case "week" -> today.minusDays(today.getDayOfWeek().getValue() - 1L);
            default -> today.withDayOfMonth(1);
        };
    }
}
