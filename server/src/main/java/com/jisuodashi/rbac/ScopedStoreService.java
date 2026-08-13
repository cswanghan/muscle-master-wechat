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

    public RbacDtos.StoreItem get(long storeId) {
        StoreScopeContext.require().assertContains(storeId);
        return RbacDtos.StoreItem.from(directory.find(storeId)
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "门店不存在")));
    }

    public RbacDtos.StoreItem create(long id, RbacDtos.StoreUpsertRequest request) {
        requireText(request == null ? null : request.code(), "code");
        requireText(request == null ? null : request.name(), "name");
        String code = request.code().trim();
        if (directory.list().stream().anyMatch(s -> code.equals(s.code()))
                || directory.find(id).isPresent()) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "门店编码已存在");
        }
        ScopedStore created = new ScopedStore(id, code, request.name().trim(), statusOr(request.status(), 1));
        directory.insert(created);
        return RbacDtos.StoreItem.from(created);
    }

    public RbacDtos.StoreItem update(long storeId, RbacDtos.StoreUpsertRequest request) {
        StoreScopeContext.require().assertContains(storeId);
        ScopedStore existing = directory.find(storeId)
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "门店不存在"));
        requireText(request == null ? null : request.name(), "name");
        ScopedStore next = new ScopedStore(
                existing.id(),
                existing.code(),
                request.name().trim(),
                request.status() == null ? existing.status() : request.status());
        directory.update(next);
        return RbacDtos.StoreItem.from(next);
    }

    public void delete(long storeId) {
        StoreScopeContext.require().assertContains(storeId);
        directory.find(storeId).orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "门店不存在"));
        directory.softDelete(storeId);
    }

    private static int statusOr(Integer status, int fallback) {
        return status == null ? fallback : status;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, field + " 不能为空");
        }
    }
}
