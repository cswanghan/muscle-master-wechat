package com.jisuodashi.catalog;

/** V3 demo IDs shared with Flyway fixtures. */
public final class DemoCatalogIds {

    public static final long STORE = 3_100_000_000_000_000_001L;
    public static final long STORE_EAST = 3_100_000_000_000_000_002L;
    /** Deliberately outside gray.store-ids: the fixture GrayApiTest hides behind. */
    public static final long STORE_DARK = 3_100_000_000_000_000_003L;
    public static final long THERAPIST_LIN = 3_100_000_000_000_000_401L;
    public static final long THERAPIST_CHEN = 3_100_000_000_000_000_402L;
    public static final long THERAPIST_ZHOU = 3_100_000_000_000_000_403L;
    public static final long PROJECT_P60 = 3_100_000_000_000_000_501L;
    public static final long PROJECT_P45 = 3_100_000_000_000_000_502L;
    public static final long PROJECT_P90 = 3_100_000_000_000_000_503L;
    /** ¥688: above PaymentService.APPROVAL_THRESHOLD_FEN, so refunding it needs approval. */
    public static final long PROJECT_P120 = 3_100_000_000_000_000_504L;
    public static final long SYMPTOM_NECK = 3_100_000_000_000_000_601L;
    public static final long SYMPTOM_BACK = 3_100_000_000_000_000_602L;
    public static final long SYMPTOM_SORE = 3_100_000_000_000_000_603L;
    public static final long SYMPTOM_ARM = 3_100_000_000_000_000_604L;
    public static final long SYMPTOM_LEG = 3_100_000_000_000_000_605L;
    public static final long SYMPTOM_SIT = 3_100_000_000_000_000_611L;
    public static final long SYMPTOM_SLEEP = 3_100_000_000_000_000_612L;
    public static final long SYMPTOM_BACK_PAIN = 3_100_000_000_000_000_613L;
    public static final long SYMPTOM_STIFF = 3_100_000_000_000_000_614L;
    public static final long SYMPTOM_HEAD = 3_100_000_000_000_000_615L;
    public static final long SYMPTOM_POSTPARTUM = 3_100_000_000_000_000_616L;
    public static final long SYMPTOM_FOOT = 3_100_000_000_000_000_617L;
    /** Fixture-only: no SKU mapping, for C2 empty hint. */
    public static final long SYMPTOM_OTHER = 3_100_000_000_000_000_699L;
    public static final long TEMPLATE_BASE = 3_100_000_000_000_000_700L;

    private DemoCatalogIds() {
    }

    /**
     * V3 week templates: Lin 701–707, Chen 711–717, Zhou 721–727. Every therapist
     * needs its own block — the old else-branch handed all unknown therapists the
     * same offset, so a fourth one would have collided with a third's templates.
     */
    public static long templateId(long therapistId, int weekday) {
        long offset = (therapistId - THERAPIST_LIN) * 10;
        return TEMPLATE_BASE + offset + weekday;
    }
}
