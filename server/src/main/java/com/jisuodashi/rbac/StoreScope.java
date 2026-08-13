package com.jisuodashi.rbac;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;

import java.util.Collection;
import java.util.List;
import java.util.function.Function;

public record StoreScope(DataScopeType type, List<Long> storeIds, long staffId, Long therapistId) {

    public StoreScope {
        storeIds = storeIds == null ? List.of() : List.copyOf(storeIds);
    }

    public boolean all() {
        return type == DataScopeType.ALL;
    }

    public boolean contains(long storeId) {
        return all() || storeIds.contains(storeId);
    }

    public void assertContains(long storeId) {
        if (!contains(storeId)) {
            throw new ApiException(ErrorCodes.DATA_SCOPE, "数据域拒绝");
        }
    }

    public <T> List<T> filter(Collection<T> items, Function<T, Long> storeIdFn) {
        if (all()) {
            return List.copyOf(items);
        }
        return items.stream().filter(item -> contains(storeIdFn.apply(item))).toList();
    }

    public String sqlInList() {
        if (storeIds.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < storeIds.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(storeIds.get(i));
        }
        return sb.toString();
    }
}
