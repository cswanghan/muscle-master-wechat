package com.jisuodashi.admin;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.rbac.StoreScope;
import com.jisuodashi.rbac.StoreScopeContext;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class AdminOrderService {

    public static final String VIEW_ABNORMAL_FIRST = "abnormal_first";
    public static final String VIEW_ALL = "all";
    private static final int ABNORMAL_LIMIT = 200;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final AdminOrderStore orders;

    public AdminOrderService(AdminOrderStore orders) {
        this.orders = orders;
    }

    public AdminDtos.OrderListResponse list(
            String viewRaw,
            String storeIdRaw,
            String status,
            String fromRaw,
            String toRaw,
            String cursor,
            Integer limit) {
        String view = viewRaw == null || viewRaw.isBlank() ? VIEW_ABNORMAL_FIRST : viewRaw.trim();
        if (!VIEW_ABNORMAL_FIRST.equals(view) && !VIEW_ALL.equals(view)) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "view 须为 abnormal_first 或 all");
        }
        StoreScope scope = StoreScopeContext.require();
        Long storeId = storeIdRaw == null || storeIdRaw.isBlank()
                ? null
                : AdminCatalogService.parseId(storeIdRaw, "storeId");
        if (storeId != null) {
            scope.assertContains(storeId);
        }
        List<AdminOrderRow> rows = new ArrayList<>(scope.filter(orders.list(), AdminOrderRow::storeId));
        if (storeId != null) {
            rows.removeIf(row -> row.storeId() != storeId);
        }
        if (VIEW_ABNORMAL_FIRST.equals(view)) {
            return abnormalFirst(rows);
        }
        return allView(rows, status, fromRaw, toRaw, cursor, limit);
    }

    private static AdminDtos.OrderListResponse abnormalFirst(List<AdminOrderRow> rows) {
        List<AdminDtos.OrderItem> items = rows.stream()
                .filter(AdminOrderRow::abnormal)
                .sorted(Comparator.comparingLong(AdminOrderRow::id).reversed())
                .limit(ABNORMAL_LIMIT)
                .map(row -> toItem(row, true))
                .toList();
        return new AdminDtos.OrderListResponse(items, null, VIEW_ABNORMAL_FIRST);
    }

    private static AdminDtos.OrderListResponse allView(
            List<AdminOrderRow> rows,
            String status,
            String fromRaw,
            String toRaw,
            String cursorRaw,
            Integer limit) {
        int size = normalizeLimit(limit);
        LocalDate from = parseDay(fromRaw, "from");
        LocalDate to = parseDay(toRaw, "to");
        CreatedCursor cursor = parseCursor(cursorRaw);
        List<AdminOrderRow> filtered = rows.stream()
                .filter(row -> status == null || status.isBlank() || status.equals(row.status()))
                .filter(row -> from == null || !row.createdAt().toLocalDate().isBefore(from))
                .filter(row -> to == null || !row.createdAt().toLocalDate().isAfter(to))
                .sorted(Comparator
                        .comparing(AdminOrderRow::createdAt).reversed()
                        .thenComparing(Comparator.comparingLong(AdminOrderRow::id).reversed()))
                .toList();
        List<AdminOrderRow> page = new ArrayList<>();
        for (AdminOrderRow row : filtered) {
            if (cursor != null && !beforeCursor(row, cursor)) {
                continue;
            }
            page.add(row);
            if (page.size() == size + 1) {
                break;
            }
        }
        String next = null;
        if (page.size() > size) {
            page.removeLast();
            next = encode(page.getLast());
        }
        List<AdminDtos.OrderItem> items = page.stream()
                .map(row -> toItem(row, row.abnormal()))
                .toList();
        return new AdminDtos.OrderListResponse(items, next, VIEW_ALL);
    }

    private static boolean beforeCursor(AdminOrderRow row, CreatedCursor cursor) {
        int cmp = row.createdAt().compareTo(cursor.createdAt());
        if (cmp != 0) {
            return cmp < 0;
        }
        return row.id() < cursor.id();
    }

    private static AdminDtos.OrderItem toItem(AdminOrderRow row, boolean highlight) {
        return new AdminDtos.OrderItem(
                String.valueOf(row.id()),
                row.orderNo(),
                String.valueOf(row.storeId()),
                String.valueOf(row.therapistId()),
                row.status(),
                row.serviceDate() == null ? null : row.serviceDate().toString(),
                row.createdAt() == null ? null : row.createdAt().format(TS),
                row.payableFen(),
                highlight);
    }

    private static int normalizeLimit(Integer limit) {
        int size = limit == null ? 20 : limit;
        if (size < 1 || size > 100) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "limit 须为 1–100");
        }
        return size;
    }

    private static LocalDate parseDay(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, field + " 无效");
        }
    }

    private static CreatedCursor parseCursor(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int split = raw.lastIndexOf('_');
        if (split <= 0 || split == raw.length() - 1) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "cursor 无效");
        }
        try {
            LocalDateTime createdAt = LocalDateTime.parse(raw.substring(0, split), TS);
            long id = Long.parseLong(raw.substring(split + 1));
            return new CreatedCursor(createdAt, id);
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "cursor 无效");
        }
    }

    private static String encode(AdminOrderRow row) {
        return row.createdAt().format(TS) + "_" + row.id();
    }

    private record CreatedCursor(LocalDateTime createdAt, long id) {
    }
}
