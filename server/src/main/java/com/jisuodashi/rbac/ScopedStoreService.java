package com.jisuodashi.rbac;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ScopedStoreService {

    private final ScopedStoreDirectory directory;

    public ScopedStoreService(ScopedStoreDirectory directory) {
        this.directory = directory;
    }

    public RbacDtos.StoreListResponse list() {
        StoreScope scope = StoreScopeContext.require();
        List<RbacDtos.StoreItem> items = scope.filter(directory.list(), ScopedStore::id).stream()
                .map(RbacDtos.StoreItem::from)
                .toList();
        List<String> storeIds = scope.storeIds().stream().map(String::valueOf).toList();
        return new RbacDtos.StoreListResponse(items, scope.type().name(), storeIds);
    }

    public RbacDtos.StoreItem updateStatus(long storeId, int status) {
        StoreScopeContext.require().assertContains(storeId);
        ScopedStore existing = directory.find(storeId)
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "门店不存在"));
        directory.updateStatus(storeId, status);
        return RbacDtos.StoreItem.from(existing.withStatus(status));
    }
}
