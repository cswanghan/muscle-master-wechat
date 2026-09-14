package com.jisuodashi.finance;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface FinanceStore {

    void insertExpense(FinanceModels.Expense row);

    void updateExpense(FinanceModels.Expense row);

    Optional<FinanceModels.Expense> findExpense(long id);

    List<FinanceModels.Expense> listExpenses(long storeId, String status, LocalDate from, LocalDate to);

    Optional<FinanceModels.Payroll> findPayroll(long therapistId);

    void upsertPayroll(FinanceModels.Payroll row);

    List<FinanceModels.Payroll> listPayrolls(long storeId);
}
