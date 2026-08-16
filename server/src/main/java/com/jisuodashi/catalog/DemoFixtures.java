package com.jisuodashi.catalog;

import com.jisuodashi.auth.DemoStaffIds;

import java.util.ArrayList;
import java.util.List;

/**
 * One place defining the demo roster, so the catalog, the staff users, the bed
 * inventory and the slot seed cannot drift apart. Each store gets
 * {@link #PER_STORE} therapists and the same number of beds — one each, since a
 * therapist without a free bed cannot take a booking however open the schedule
 * looks.
 *
 * <p>The first three of store 1 keep the ids and names the earlier fixtures used
 * (401/402/403, 林晓/陈默/周可), so anything pinned to them still resolves.
 */
public final class DemoFixtures {

    public static final int PER_STORE = 10;

    private static final long THERAPIST_BASE_MAIN = 3_100_000_000_000_000_401L;
    private static final long THERAPIST_BASE_EAST = 3_100_000_000_000_000_421L;
    private static final long STAFF_BASE_MAIN = DemoStaffIds.T1;
    private static final long STAFF_BASE_EAST = 3_100_000_000_000_000_324L;
    public static final long ROOM_MAIN = 3_100_000_000_000_000_101L;
    public static final long ROOM_EAST = 3_100_000_000_000_000_102L;
    private static final long BED_BASE_MAIN = 3_100_000_000_000_000_201L;
    private static final long BED_BASE_EAST = 3_100_000_000_000_000_211L;

    private static final String[] NAMES_MAIN = {
        "林晓", "陈默", "周可", "苏岚", "郑衡", "许清", "何岸", "沈砚", "柳青", "范舟",
    };
    private static final String[] NAMES_EAST = {
        "白露", "秦禾", "孟舒", "曹宇", "潘屿", "程澈", "路遥", "韩汀", "邵岩", "章棠",
    };
    /** Senior / middle / junior repeating, so the list is not uniform. */
    private static final String[] LEVELS = {
        "SENIOR", "MIDDLE", "JUNIOR", "SENIOR", "MIDDLE", "JUNIOR", "MIDDLE", "SENIOR", "JUNIOR", "MIDDLE",
    };

    public record TherapistSeed(
            long therapistId,
            long staffUserId,
            long storeId,
            String employeeNo,
            String username,
            String name,
            String level,
            int ratingX100
    ) {
    }

    public record BedSeed(long bedId, long storeId, long roomId, String name, int sortNo) {
    }

    private DemoFixtures() {
    }

    public static List<TherapistSeed> therapists() {
        List<TherapistSeed> out = new ArrayList<>();
        out.addAll(roster(DemoCatalogIds.STORE, THERAPIST_BASE_MAIN, STAFF_BASE_MAIN, NAMES_MAIN, "T", "t"));
        out.addAll(roster(DemoCatalogIds.STORE_EAST, THERAPIST_BASE_EAST, STAFF_BASE_EAST, NAMES_EAST, "E", "e"));
        return List.copyOf(out);
    }

    public static List<TherapistSeed> therapistsOf(long storeId) {
        return therapists().stream().filter(t -> t.storeId() == storeId).toList();
    }

    public static List<BedSeed> beds() {
        List<BedSeed> out = new ArrayList<>();
        for (int i = 0; i < PER_STORE; i++) {
            out.add(new BedSeed(
                    BED_BASE_MAIN + i, DemoCatalogIds.STORE, ROOM_MAIN, (i + 1) + "号床", i + 1));
            out.add(new BedSeed(
                    BED_BASE_EAST + i, DemoCatalogIds.STORE_EAST, ROOM_EAST, (i + 1) + "号床", i + 1));
        }
        return List.copyOf(out);
    }

    private static List<TherapistSeed> roster(
            long storeId, long therapistBase, long staffBase, String[] names, String noPrefix, String userPrefix) {
        List<TherapistSeed> out = new ArrayList<>();
        for (int i = 0; i < PER_STORE; i++) {
            out.add(new TherapistSeed(
                    therapistBase + i,
                    staffBase + i,
                    storeId,
                    String.format("%s%03d", noPrefix, i + 1),
                    "demo." + userPrefix + (i + 1),
                    names[i],
                    LEVELS[i],
                    500 - i * 5));
        }
        return out;
    }
}
