package com.jisuodashi.catalog;

import java.util.List;

/** D13: coalesce(slot.override, store_project.price_fen, project.price_fen). P0 override is usually null. */
public final class Pricing {

    private Pricing() {
    }

    public static long priceFen(Long slotOverrideFen, Long storeProjectFen, long projectFen) {
        if (slotOverrideFen != null) {
            return slotOverrideFen;
        }
        if (storeProjectFen != null) {
            return storeProjectFen;
        }
        return projectFen;
    }

    public static long priceFen(
            Long storeId,
            long projectId,
            CatalogModels.Project project,
            List<CatalogModels.StoreProject> storeProjects) {
        Long storeFen = null;
        if (storeId != null) {
            for (CatalogModels.StoreProject sp : storeProjects) {
                if (sp.storeId() == storeId && sp.projectId() == projectId && sp.status() == 1 && sp.priceFen() != null) {
                    storeFen = sp.priceFen();
                    break;
                }
            }
        }
        return priceFen(null, storeFen, project.priceFen());
    }
}
