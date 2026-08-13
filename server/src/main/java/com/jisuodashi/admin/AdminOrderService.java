package com.jisuodashi.admin;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.rbac.StoreScope;
import com.jisuodashi.rbac.StoreScopeContext;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

@Service
public class AdminOrderService {

    public static final String VIEW_ABNORMAL_FIRST = "abnormal_first";
    public static final String VIEW_ALL = "all";
    private static final int ABNORMAL_LIMIT = 200;

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
        if (VIEW_ABNORMAL_FIRST.equals(view)) {
            List<AdminDtos.OrderItem> items = orders.listAbnormalFirst(storeId, ABNORMAL_LIMIT).stream()
                    .map(row -> toItem(row, true))
                    .toList();
            return new AdminDtos.OrderListResponse(items, null, VIEW_ABNORMAL_FIRST);
        }
        return allView(storeId, status, fromRaw, toRaw, cursor, limit);
    }

    private AdminDtos.OrderListResponse allView(
            Long storeId,
            String status,
            String fromRaw,
            String toRaw,
            String cursorRaw,
            Integer limit) {
        int size = normalizeLimit(limit);
        List<AdminOrderRow> page = new ArrayList<>(orders.listAll(
                storeId, status, parseDay(fromRaw, "from"), parseDay(toRaw, "to"),
                AdminOrderCursors.parse(cursorRaw), size + 1));
        String next = null;
        if (page.size() > size) {
            page.removeLast();
            next = AdminOrderCursors.encode(page.getLast());
        }
        List<AdminDtos.OrderItem> items = page.stream()
                .map(row -> toItem(row, row.abnormal()))
                .toList();
        return new AdminDtos.OrderListResponse(items, next, VIEW_ALL);
    }

    private static AdminDtos.OrderItem toItem(AdminOrderRow row, boolean highlight) {
        return new AdminDtos.OrderItem(
                String.valueOf(row.id()),
                row.orderNo(),
                String.valueOf(row.storeId()),
                String.valueOf(row.therapistId()),
                row.status(),
                row.serviceDate() == null ? null : row.serviceDate().toString(),
                AdminOrderCursors.formatCreatedAt(row.createdAt()),
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
}
