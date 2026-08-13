package com.jisuodashi.rbac;

import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.catalog.CatalogModels;
import com.jisuodashi.catalog.CatalogRepository;
import org.springframework.stereotype.Component;

@Component
public class StoreScopeResolver {

    private final CatalogRepository catalog;

    public StoreScopeResolver(CatalogRepository catalog) {
        this.catalog = catalog;
    }

    public StoreScope resolve(JwtPrincipal principal) {
        DataScopeType type = DataScopeType.parse(principal.scopeType());
        Long therapistId = null;
        if (type == DataScopeType.SELF && principal.staffId() != null) {
            therapistId = catalog.listTherapists().stream()
                    .filter(t -> t.staffUserId() == principal.staffId())
                    .map(CatalogModels.Therapist::id)
                    .findFirst()
                    .orElse(null);
        }
        return new StoreScope(type, principal.storeIds(), principal.subjectId(), therapistId);
    }
}
