package com.jisuodashi.finance;

import com.jisuodashi.common.ApiResponse;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.growth.GrowthService;
import com.jisuodashi.rbac.Audited;
import com.jisuodashi.rbac.RequirePerm;
import com.jisuodashi.rbac.StoreScoped;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 财务：支出提报审批、薪资口径、六张月度报表、三张客户报表。 */
@RestController
@RequestMapping("/api/v1/f/finance")
public class FinanceController {

    private final ExpenseService expenses;
    private final FinanceReportService reports;
    private final AppClock clock;

    public FinanceController(
            ExpenseService expenses, FinanceReportService reports, AppClock clock) {
        this.expenses = expenses;
        this.reports = reports;
        this.clock = clock;
    }

    // ── 支出 ──
    @PostMapping("/expenses")
    @StoreScoped
    @RequirePerm("expense:write")
    @Audited(action = "EXPENSE_SUBMIT", resourceType = "EXPENSE")
    public ApiResponse<FinanceDtos.ExpenseItem> submit(
            @RequestBody FinanceDtos.ExpenseRequest request) {
        return ApiResponse.ok(expenses.submit(request));
    }

    @GetMapping("/expenses")
    @StoreScoped
    @RequirePerm("expense:write")
    public ApiResponse<FinanceDtos.ExpenseListResponse> listExpenses(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {
        return ApiResponse.ok(expenses.list(status, from, to));
    }

    @PostMapping("/expenses/{id}/approve")
    @StoreScoped
    @RequirePerm("expense:approve")
    @Audited(action = "EXPENSE_APPROVE", resourceType = "EXPENSE")
    public ApiResponse<FinanceDtos.ExpenseItem> approve(@PathVariable("id") String id) {
        return ApiResponse.ok(expenses.decide(id, true, null));
    }

    @PostMapping("/expenses/{id}/reject")
    @StoreScoped
    @RequirePerm("expense:approve")
    @Audited(action = "EXPENSE_REJECT", resourceType = "EXPENSE")
    public ApiResponse<FinanceDtos.ExpenseItem> reject(
            @PathVariable("id") String id,
            @RequestParam(value = "reason", required = false) String reason) {
        return ApiResponse.ok(expenses.decide(id, false, reason));
    }

    @PostMapping("/payroll-config")
    @StoreScoped
    @RequirePerm("payroll:config")
    @Audited(action = "PAYROLL_CONFIG", resourceType = "THERAPIST")
    public ApiResponse<String> savePayroll(
            @RequestBody FinanceDtos.PayrollConfigRequest request) {
        expenses.savePayroll(request);
        return ApiResponse.ok("ok");
    }

    // ── 报表 ──
    @GetMapping("/reports/consume")
    @StoreScoped
    @RequirePerm("finance:report")
    public ApiResponse<FinanceDtos.ConsumeReport> consume(
            @RequestParam(value = "month", required = false) String month) {
        LocalDate from = monthStart(month);
        return ApiResponse.ok(reports.consumeReport(ExpenseService.storeId(), from, monthEnd(from)));
    }

    @GetMapping("/reports/trial")
    @StoreScoped
    @RequirePerm("finance:report")
    public ApiResponse<FinanceDtos.TrialReport> trial(
            @RequestParam(value = "month", required = false) String month) {
        LocalDate from = monthStart(month);
        return ApiResponse.ok(reports.trialReport(ExpenseService.storeId(), from, monthEnd(from)));
    }

    @GetMapping("/reports/refund")
    @StoreScoped
    @RequirePerm("finance:report")
    public ApiResponse<FinanceDtos.RefundReport> refund(
            @RequestParam(value = "month", required = false) String month) {
        LocalDate from = monthStart(month);
        return ApiResponse.ok(reports.refundReport(ExpenseService.storeId(), from, monthEnd(from)));
    }

    @GetMapping("/reports/payroll")
    @StoreScoped
    @RequirePerm("finance:report")
    public ApiResponse<FinanceDtos.PayrollReport> payroll(
            @RequestParam(value = "month", required = false) String month) {
        LocalDate from = monthStart(month);
        return ApiResponse.ok(reports.payrollReport(ExpenseService.storeId(), from, monthEnd(from)));
    }

    /** 库存课 = 卖出去还没上完的课时价值。这是负债，不是资产。 */
    @GetMapping("/reports/inventory")
    @StoreScoped
    @RequirePerm("finance:report")
    public ApiResponse<FinanceDtos.InventoryReport> inventory() {
        return ApiResponse.ok(reports.inventoryReport(ExpenseService.storeId()));
    }

    @GetMapping("/reports/store")
    @StoreScoped
    @RequirePerm("finance:report")
    public ApiResponse<FinanceDtos.StoreReport> store(
            @RequestParam(value = "month", required = false) String month) {
        LocalDate from = monthStart(month);
        return ApiResponse.ok(reports.storeReport(ExpenseService.storeId(), from, monthEnd(from)));
    }

    /** 客户报表：{@code kind} 取 renew / expired / dormant。 */
    @GetMapping("/reports/customers")
    @StoreScoped
    @RequirePerm("finance:report")
    public ApiResponse<FinanceDtos.CustomerReport> customers(
            @RequestParam(value = "kind", required = false) String kind) {
        return ApiResponse.ok(reports.customerReport(ExpenseService.storeId(), kind));
    }

    private LocalDate monthStart(String raw) {
        if (raw == null || raw.isBlank()) {
            return clock.today().withDayOfMonth(1);
        }
        try {
            return LocalDate.parse(raw.trim() + "-01");
        } catch (RuntimeException e) {
            return clock.today().withDayOfMonth(1);
        }
    }

    private static LocalDate monthEnd(LocalDate start) {
        return start.withDayOfMonth(start.lengthOfMonth());
    }
}
