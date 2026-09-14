package com.jisuodashi.rbac;

import com.jisuodashi.catalog.CatalogModels;
import com.jisuodashi.catalog.CatalogRepository;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 「当前该看哪家店」的唯一出处。
 *
 * <p>存在的理由：{@code StoreScope.storeIds()} 对**超管是空的**（数据域 ALL 的语义是
 * "全都能看"，不是"属于某几家"）。直接取 {@code getFirst()} 会让超管在所有按门店的
 * 报表页上撞到"请指定门店"—— 权限最大的人反而什么都看不了。
 *
 * <p>三级 fallback：显式传入 → 数据域里的第一家 → 第一家营业门店。
 * 超管要看别家，由前端带 {@code storeId} 覆盖。
 */
@Component
public class ScopedStoreResolver {

    private final CatalogRepository catalog;

    public ScopedStoreResolver(CatalogRepository catalog) {
        this.catalog = catalog;
    }

    public long resolve(String explicitStoreId) {
        if (explicitStoreId != null && !explicitStoreId.isBlank()) {
            long id = parse(explicitStoreId);
            StoreScope scope = StoreScopeContext.get();
            // 显式指定也要过数据域：店长不能拿别人的 storeId 越权看账。
            if (scope != null) {
                scope.assertContains(id);
            }
            return id;
        }
        StoreScope scope = StoreScopeContext.get();
        if (scope != null && !scope.storeIds().isEmpty()) {
            return scope.storeIds().getFirst();
        }
        return firstOpenStore();
    }

    public long resolve() {
        return resolve(null);
    }

    /** 供前端渲染门店切换器：超管拿全部，其余拿自己数据域内的。 */
    public List<CatalogModels.Store> visibleStores() {
        StoreScope scope = StoreScopeContext.get();
        List<CatalogModels.Store> open = catalog.listStores().stream()
                .filter(s -> s.status() == 1)
                .sorted(Comparator.comparingLong(CatalogModels.Store::id))
                .toList();
        if (scope == null || scope.all()) {
            return open;
        }
        return open.stream().filter(s -> scope.contains(s.id())).toList();
    }

    private long firstOpenStore() {
        return catalog.listStores().stream()
                .filter(s -> s.status() == 1)
                .min(Comparator.comparingLong(CatalogModels.Store::id))
                .map(CatalogModels.Store::id)
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "没有营业中的门店"));
    }

    private static long parse(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "storeId 无效");
        }
    }
}
