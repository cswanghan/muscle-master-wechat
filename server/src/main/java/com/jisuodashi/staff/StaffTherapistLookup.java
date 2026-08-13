package com.jisuodashi.staff;

import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.catalog.CatalogModels;
import com.jisuodashi.catalog.CatalogRepository;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;
import org.springframework.stereotype.Component;

@Component
public class StaffTherapistLookup {

    private final CatalogRepository catalog;

    public StaffTherapistLookup(CatalogRepository catalog) {
        this.catalog = catalog;
    }

    public CatalogModels.Therapist requireTherapist(JwtPrincipal principal) {
        return catalog.listTherapists().stream()
                .filter(t -> t.staffUserId() == principal.subjectId())
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCodes.FORBIDDEN, "无功能权限"));
    }
}
