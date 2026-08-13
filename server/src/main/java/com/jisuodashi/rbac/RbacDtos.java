package com.jisuodashi.rbac;

import java.util.List;

public final class RbacDtos {

    private RbacDtos() {
    }

    public record StoreItem(String storeId, String code, String name, int status) {
        public static StoreItem from(ScopedStore store) {
            return new StoreItem(String.valueOf(store.id()), store.code(), store.name(), store.status());
        }
    }

    public record StoreListResponse(List<StoreItem> items, String scopeType, List<String> storeIds) {
    }

    public record StoreStatusRequest(int status) {
    }

    public record DeskNoteRequest(String storeId, String content) {
    }

    public record DeskNoteResponse(String id, String storeId, String content) {
    }

    public record TreatmentNoteItem(String id, String orderId, String content, String createdAt) {
    }

    public record TreatmentNoteList(List<TreatmentNoteItem> items) {
    }
}
