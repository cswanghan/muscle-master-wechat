package com.jisuodashi.admin;

import com.jisuodashi.rbac.StoreScope;
import com.jisuodashi.rbac.StoreScopeContext;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public interface AdminOrderStore {

    List<AdminOrderRow> list();

    default void resetDemo() {
    }

    /** Heap fallback for in-memory. JDBC overrides with SQL + LIMIT. */
    default List<AdminOrderRow> listAbnormalFirst(Long storeId, int limit) {
        return scoped(list(), storeId).stream()
                .filter(AdminOrderRow::abnormal)
                .sorted(Comparator.comparingLong(AdminOrderRow::id).reversed())
                .limit(limit)
                .toList();
    }

    /** Heap fallback. `fetch` is page size + 1. JDBC overrides with a `(created_at, id)` seek. */
    default List<AdminOrderRow> listAll(
            Long storeId,
            String status,
            LocalDate from,
            LocalDate to,
            AdminOrderCursors.Cursor cursor,
            int fetch) {
        List<AdminOrderRow> filtered = scoped(list(), storeId).stream()
                .filter(row -> status == null || status.isBlank() || status.equals(row.status()))
                .filter(row -> from == null || !row.createdAt().toLocalDate().isBefore(from))
                .filter(row -> to == null || !row.createdAt().toLocalDate().isAfter(to))
                .sorted(Comparator
                        .comparing(AdminOrderRow::createdAt).reversed()
                        .thenComparing(Comparator.comparingLong(AdminOrderRow::id).reversed()))
                .toList();
        List<AdminOrderRow> page = new ArrayList<>();
        for (AdminOrderRow row : filtered) {
            if (cursor != null && !AdminOrderCursors.beforeCursor(row, cursor)) {
                continue;
            }
            page.add(row);
            if (page.size() == fetch) {
                break;
            }
        }
        return page;
    }

    private static List<AdminOrderRow> scoped(List<AdminOrderRow> rows, Long storeId) {
        StoreScope scope = StoreScopeContext.get();
        List<AdminOrderRow> out = scope == null ? rows : scope.filter(rows, AdminOrderRow::storeId);
        if (storeId == null) {
            return out;
        }
        return out.stream().filter(row -> row.storeId() == storeId).toList();
    }
}
