package com.jisuodashi.finance;

import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.StaffUser;
import com.jisuodashi.auth.StaffUserRepository;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.growth.GrowthService;
import com.jisuodashi.rbac.StoreScope;
import com.jisuodashi.rbac.StoreScopeContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 支出提报与审批。审批通过的才计入成本，否则谁都能把利润表填花。 */
@Service
public class ExpenseService {

    private final FinanceStore store;
    private final StaffUserRepository staff;
    private final SnowflakeIdGenerator ids;
    private final AppClock clock;

    public ExpenseService(
            FinanceStore store, StaffUserRepository staff,
            SnowflakeIdGenerator ids, AppClock clock) {
        this.store = store;
        this.staff = staff;
        this.ids = ids;
        this.clock = clock;
    }

    public FinanceDtos.ExpenseItem submit(FinanceDtos.ExpenseRequest req) {
        JwtPrincipal me = AuthContext.requireStaff();
        if (req.amountFen() <= 0) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "金额必须大于 0");
        }
        String cat = req.category() == null || req.category().isBlank() ? "MATERIAL" : req.category();
        if (!FinanceModels.CATEGORIES.contains(cat)) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "未知的支出类目");
        }
        Instant now = Instant.now(clock.clock());
        FinanceModels.Expense row = new FinanceModels.Expense(
                ids.nextId(), storeId(), cat, req.amountFen(),
                GrowthService.parseDate(req.happenedOn(), clock.today()),
                req.vendor(), req.remark(), req.proofUrl(),
                FinanceModels.STATUS_PENDING, me.staffId(), null, null, null, now, now);
        store.insertExpense(row);
        return toItem(row);
    }

    /**
     * 审批。提报人不能自批 —— 一个人既能提又能批，凭证审批就只是走个形式。
     */
    public FinanceDtos.ExpenseItem decide(String idRaw, boolean approve, String rejectReason) {
        JwtPrincipal me = AuthContext.requireStaff();
        FinanceModels.Expense cur = store.findExpense(GrowthService.parseId(idRaw))
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "支出单不存在"));
        if (!FinanceModels.STATUS_PENDING.equals(cur.status())) {
            throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "该支出已处理");
        }
        if (cur.submittedBy() == me.staffId()) {
            throw new ApiException(ErrorCodes.FORBIDDEN, "不能审批自己提交的支出");
        }
        Instant now = Instant.now(clock.clock());
        FinanceModels.Expense next = new FinanceModels.Expense(
                cur.id(), cur.storeId(), cur.category(), cur.amountFen(), cur.happenedOn(),
                cur.vendor(), cur.remark(), cur.proofUrl(),
                approve ? FinanceModels.STATUS_APPROVED : FinanceModels.STATUS_REJECTED,
                cur.submittedBy(), me.staffId(), now,
                approve ? null : rejectReason, cur.createdAt(), now);
        store.updateExpense(next);
        return toItem(next);
    }

    public FinanceDtos.ExpenseListResponse list(String status, String fromRaw, String toRaw) {
        LocalDate today = clock.today();
        LocalDate from = GrowthService.parseDate(fromRaw, today.withDayOfMonth(1));
        LocalDate to = GrowthService.parseDate(toRaw, today);
        List<FinanceModels.Expense> rows = store.listExpenses(
                storeId(), status == null || status.isBlank() ? null : status, from, to);
        long approved = rows.stream()
                .filter(FinanceModels.Expense::approved)
                .mapToLong(FinanceModels.Expense::amountFen).sum();
        return new FinanceDtos.ExpenseListResponse(
                rows.stream().map(this::toItem).toList(),
                (int) rows.stream()
                        .filter(e -> FinanceModels.STATUS_PENDING.equals(e.status())).count(),
                FinanceReportService.yuan(approved));
    }

    public void savePayroll(FinanceDtos.PayrollConfigRequest req) {
        long therapistId = GrowthService.parseId(req.therapistId());
        Instant now = Instant.now(clock.clock());
        FinanceModels.Payroll cur = store.findPayroll(therapistId).orElse(null);
        store.upsertPayroll(new FinanceModels.Payroll(
                cur == null ? ids.nextId() : cur.id(), therapistId, storeId(),
                req.baseSalaryFen(), req.lessonFeeFen(), req.saleRateX100(),
                cur == null ? clock.today() : cur.effectiveOn(),
                cur == null ? now : cur.createdAt(), now));
    }

    private FinanceDtos.ExpenseItem toItem(FinanceModels.Expense e) {
        return new FinanceDtos.ExpenseItem(
                String.valueOf(e.id()), e.category(), FinanceModels.categoryLabel(e.category()),
                FinanceReportService.yuan(e.amountFen()), e.happenedOn().toString(),
                e.vendor(), e.remark(), e.proofUrl(),
                e.status(), statusLabel(e.status()),
                staff.findById(e.submittedBy()).map(StaffUser::getName).orElse("—"),
                e.rejectReason());
    }

    static String statusLabel(String s) {
        return switch (s == null ? "" : s) {
            case FinanceModels.STATUS_APPROVED -> "已通过";
            case FinanceModels.STATUS_REJECTED -> "已驳回";
            default -> "待审批";
        };
    }

    static long storeId() {
        StoreScope scope = StoreScopeContext.get();
        if (scope == null || scope.storeIds().isEmpty()) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "请指定门店");
        }
        return scope.storeIds().getFirst();
    }
}
