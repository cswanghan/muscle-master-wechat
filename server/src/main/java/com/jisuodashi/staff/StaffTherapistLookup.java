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

    /** 展示用姓名。查不到时返回 id 而不是空，免得界面上出现一片空白。 */
    public String nameOf(long therapistId) {
        return catalog.listTherapists().stream()
                .filter(t -> t.id() == therapistId)
                .map(CatalogModels.Therapist::name)
                .findFirst()
                .orElse(String.valueOf(therapistId));
    }

    /**
     * therapist id → staff_user id。
     *
     * <p>两个 id 空间很容易混：档案里存的是 therapist，而回访、考勤、审计存的都是
     * staff_user。跨过来必须显式转一次。
     */
    public java.util.Optional<Long> staffUserIdOf(long therapistId) {
        return catalog.listTherapists().stream()
                .filter(t -> t.id() == therapistId)
                .map(CatalogModels.Therapist::staffUserId)
                .findFirst();
    }
}
