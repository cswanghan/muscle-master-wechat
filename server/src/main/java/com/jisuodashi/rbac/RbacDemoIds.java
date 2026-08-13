package com.jisuodashi.rbac;

import com.jisuodashi.auth.DemoStaffIds;
import com.jisuodashi.catalog.DemoCatalogIds;

/** Extra fixture store for scope demos. Not in V3 (C catalog stays 1 store). */
public final class RbacDemoIds {

    public static final long STORE = DemoStaffIds.STORE;
    public static final long STORE_EAST = 3_100_000_000_000_000_002L;
    public static final long NOTE_ORDER = 9_100_000_000_000_000_001L;
    public static final long NOTE_ID = 9_100_000_000_000_000_101L;
    public static final long THERAPIST_LIN = DemoCatalogIds.THERAPIST_LIN;

    private RbacDemoIds() {
    }
}
