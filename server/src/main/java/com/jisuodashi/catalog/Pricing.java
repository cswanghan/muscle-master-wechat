package com.jisuodashi.catalog;

import java.util.List;

/** D13: coalesce(slot.override, store_project.price_fen, project.price_fen). P0 override is always null. */
public final class Pricing {

    private Pricing() {
    }

    public static long priceFen(
            Long storeId,
            long projectId,
            CatalogModels.Project project,
            List<CatalogModels.StoreProject> storeProjects) {
        if (storeId != null) {
            for (CatalogModels.StoreProject sp : storeProjects) {
                if (sp.storeId() == storeId && sp.projectId() == projectId && sp.status() == 1 && sp.priceFen() != null) {
                    return sp.priceFen();
                }
            }
        }
        return project.priceFen();
    }
}
