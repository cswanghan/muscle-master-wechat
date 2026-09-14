package com.jisuodashi.finance;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("dev")
public class InMemoryFinanceStore implements FinanceStore {

    private final ConcurrentHashMap<Long, FinanceModels.Expense> expenses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, FinanceModels.Payroll> payrolls = new ConcurrentHashMap<>();

    @Override
    public void insertExpense(FinanceModels.Expense row) {
        expenses.put(row.id(), row);
    }

    @Override
    public void updateExpense(FinanceModels.Expense row) {
        expenses.put(row.id(), row);
    }

    @Override
    public Optional<FinanceModels.Expense> findExpense(long id) {
        return Optional.ofNullable(expenses.get(id));
    }

    @Override
    public List<FinanceModels.Expense> listExpenses(
            long storeId, String status, LocalDate from, LocalDate to) {
        return expenses.values().stream()
                .filter(e -> e.storeId() == storeId)
                .filter(e -> status == null || status.equals(e.status()))
                .filter(e -> !e.happenedOn().isBefore(from) && !e.happenedOn().isAfter(to))
                .sorted(Comparator.comparing(FinanceModels.Expense::happenedOn).reversed())
                .toList();
    }

    @Override
    public Optional<FinanceModels.Payroll> findPayroll(long therapistId) {
        return payrolls.values().stream()
                .filter(p -> p.therapistId() == therapistId)
                .findFirst();
    }

    @Override
    public void upsertPayroll(FinanceModels.Payroll row) {
        payrolls.put(row.therapistId(), row);
    }

    @Override
    public List<FinanceModels.Payroll> listPayrolls(long storeId) {
        return payrolls.values().stream()
                .filter(p -> p.storeId() == storeId)
                .sorted(Comparator.comparingLong(FinanceModels.Payroll::therapistId))
                .toList();
    }
}
