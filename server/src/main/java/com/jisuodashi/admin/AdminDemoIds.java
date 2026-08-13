package com.jisuodashi.admin;

import com.jisuodashi.catalog.DemoCatalogIds;
import com.jisuodashi.rbac.RbacDemoIds;

/** In-memory admin order fixtures (dev). Not in V3. */
public final class AdminDemoIds {

    public static final long STORE = DemoCatalogIds.STORE;
    public static final long STORE_EAST = RbacDemoIds.STORE_EAST;
    public static final long THERAPIST_LIN = DemoCatalogIds.THERAPIST_LIN;
    public static final long ORDER_ABNORMAL = 9_100_000_000_000_000_201L;
    public static final long ORDER_MANUAL = 9_100_000_000_000_000_202L;
    public static final long ORDER_BOOKED = 9_100_000_000_000_000_203L;
    public static final long ORDER_COMPLETED = 9_100_000_000_000_000_204L;
    public static final long ORDER_PENDING = 9_100_000_000_000_000_205L;
    public static final long ORDER_EAST = 9_100_000_000_000_000_206L;

    private AdminDemoIds() {
    }
}
