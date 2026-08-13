package com.jisuodashi.catalog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PricingTest {

    @Test
    void d13CoalesceOverrideThenStoreThenProject() {
        assertThat(Pricing.priceFen(null, null, 19800)).isEqualTo(19800);
        assertThat(Pricing.priceFen(null, 18800L, 19800)).isEqualTo(18800);
        assertThat(Pricing.priceFen(15000L, 18800L, 19800)).isEqualTo(15000);
        assertThat(Pricing.priceFen(15000L, null, 19800)).isEqualTo(15000);
    }

    @Test
    void catalogOverloadUsesListedStoreProject() {
        CatalogModels.Project project = new CatalogModels.Project(
                88, "P60", "全身推拿放松", 60, 15, 19800, null, null, 1);
        List<CatalogModels.StoreProject> listed = List.of(
                new CatalogModels.StoreProject(1, 88, 18800L, 1),
                new CatalogModels.StoreProject(1, 88, 1L, 0),
                new CatalogModels.StoreProject(2, 88, 100L, 1));
        assertThat(Pricing.priceFen(1L, 88, project, listed)).isEqualTo(18800);
        assertThat(Pricing.priceFen(9L, 88, project, listed)).isEqualTo(19800);
        assertThat(Pricing.priceFen(null, 88, project, listed)).isEqualTo(19800);
    }
}
