package com.jisuodashi.catalog;

/** V3 demo IDs shared with Flyway fixtures. */
public final class DemoCatalogIds {

    public static final long STORE = 3_100_000_000_000_000_001L;
    public static final long THERAPIST_LIN = 3_100_000_000_000_000_401L;
    public static final long THERAPIST_CHEN = 3_100_000_000_000_000_402L;
    public static final long THERAPIST_ZHOU = 3_100_000_000_000_000_403L;
    public static final long PROJECT_P60 = 3_100_000_000_000_000_501L;
    public static final long PROJECT_P45 = 3_100_000_000_000_000_502L;
    public static final long PROJECT_P90 = 3_100_000_000_000_000_503L;
    public static final long SYMPTOM_NECK = 3_100_000_000_000_000_601L;
    public static final long SYMPTOM_BACK = 3_100_000_000_000_000_602L;
    public static final long SYMPTOM_SORE = 3_100_000_000_000_000_603L;
    /** Fixture-only: no SKU mapping, for C2 empty hint. */
    public static final long SYMPTOM_OTHER = 3_100_000_000_000_000_699L;

    private DemoCatalogIds() {
    }
}
