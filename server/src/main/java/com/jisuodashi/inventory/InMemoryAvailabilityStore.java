package com.jisuodashi.inventory;

import com.jisuodashi.catalog.DemoCatalogIds;
import com.jisuodashi.catalog.DemoFixtures;
import com.jisuodashi.common.AppClock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dev + tests. H2 cannot apply V1 slot DDL. Seeds a four-state day on the
 * V3 demo store so {@code GET /c/availability} works without MySQL.
 */
@Repository
@Profile("dev")
public class InMemoryAvailabilityStore implements AvailabilityStore {

    /** Anchor for hand-built stores in unit tests; the Spring bean follows the clock instead. */
    public static final LocalDate DEMO_DATE = LocalDate.of(2026, 8, 14);
    static final int OPEN = 40;
    static final int CLOSE = 88;
    static final long BED1 = 3_100_000_000_000_000_201L;
    static final long BED2 = 3_100_000_000_000_000_202L;

    private final Map<String, TherapistSlotView> therapistSlots = new ConcurrentHashMap<>();
    private final Map<String, BedSlotView> bedSlots = new ConcurrentHashMap<>();
    private final Map<String, OccupancyView> occupancies = new ConcurrentHashMap<>();

    private final LocalDate anchor;

    /**
     * Bookings write occupancy into InMemorySlotOccupyStore, not here, so on dev the
     * two held different pictures of the same day: lockNew refused a taken slot while
     * this store still reported it FREE. Under MySQL both read one slot_occupancy
     * table, so the seam is in-memory only. Null in unit tests.
     */
    private InMemorySlotOccupyStore liveOccupancy;

    @Autowired(required = false)
    public void setLiveOccupancy(InMemorySlotOccupyStore liveOccupancy) {
        this.liveOccupancy = liveOccupancy;
    }

    public InMemoryAvailabilityStore() {
        this(DEMO_DATE);
    }

    /**
     * Seeds the demo day on whatever "today" the clock reports, so a dev run is bookable on
     * any date instead of rotting the day after a hard-coded anchor.
     */
    @Autowired
    public InMemoryAvailabilityStore(AppClock clock) {
        this(clock.today());
    }

    private InMemoryAvailabilityStore(LocalDate anchor) {
        this.anchor = anchor;
        seedFourStatesDemo();
    }

    /** Tests that want an empty calendar. */
    public static InMemoryAvailabilityStore blank() {
        return new InMemoryAvailabilityStore(DEMO_DATE, false);
    }

    private InMemoryAvailabilityStore(LocalDate anchor, boolean unused) {
        this.anchor = anchor;
    }

    /** The day {@link #seedFourStatesDemo()} wrote to. */
    public LocalDate anchor() {
        return anchor;
    }

    public void seedTherapistSlots(long therapistId, LocalDate date, int from, int toExclusive, String status) {
        seedTherapistSlots(therapistId, date, from, toExclusive, status, null);
    }

    public void seedTherapistSlots(
            long therapistId, LocalDate date, int from, int toExclusive, String status, Long overrideFen) {
        for (int slot = from; slot < toExclusive; slot++) {
            therapistSlots.put(tkey(therapistId, date, slot),
                    new TherapistSlotView(therapistId, slot, status, overrideFen));
        }
    }

    public void seedBedSlots(long bedId, LocalDate date, int from, int toExclusive, String status) {
        for (int slot = from; slot < toExclusive; slot++) {
            bedSlots.put(bkey(bedId, date, slot), new BedSlotView(bedId, slot, status));
        }
    }

    public void seedOccupancy(String resourceType, long resourceId, LocalDate date, int from, int toExclusive) {
        for (int slot = from; slot < toExclusive; slot++) {
            occupancies.put(okey(resourceType, resourceId, date, slot),
                    new OccupancyView(resourceType, resourceId, slot));
        }
    }

    public void clearOccupancy(String resourceType, long resourceId, LocalDate date, int slotNo) {
        occupancies.remove(okey(resourceType, resourceId, date, slotNo));
    }

    public void setTherapistStatus(long therapistId, LocalDate date, int slotNo, String status) {
        TherapistSlotView prev = therapistSlots.get(tkey(therapistId, date, slotNo));
        Long override = prev == null ? null : prev.priceOverrideFen();
        therapistSlots.put(tkey(therapistId, date, slotNo),
                new TherapistSlotView(therapistId, slotNo, status, override));
    }

    public void setTherapistOverride(long therapistId, LocalDate date, int slotNo, Long overrideFen) {
        TherapistSlotView prev = therapistSlots.get(tkey(therapistId, date, slotNo));
        String status = prev == null ? SlotStatus.FREE : prev.status();
        therapistSlots.put(tkey(therapistId, date, slotNo),
                new TherapistSlotView(therapistId, slotNo, status, overrideFen));
    }

    public void setBedStatus(long bedId, LocalDate date, int slotNo, String status) {
        bedSlots.put(bkey(bedId, date, slotNo), new BedSlotView(bedId, slotNo, status));
    }

    /** 林晓 REST+LOCKED, 周可 BOOKED+BUFFER, 陈默 FREE — starts only on FREE. */
    public final void seedFourStatesDemo() {
        therapistSlots.clear();
        bedSlots.clear();
        occupancies.clear();
        long t1 = DemoCatalogIds.THERAPIST_LIN;
        long t3 = DemoCatalogIds.THERAPIST_ZHOU;

        // Every therapist and bed in the fixtures gets an open day: a therapist whose
        // store has no free bed can never be booked however open the schedule looks.
        for (int day = 0; day < 60; day++) {
            LocalDate date = anchor.plusDays(day);
            for (DemoFixtures.TherapistSeed s : DemoFixtures.therapists()) {
                seedTherapistSlots(s.therapistId(), date, OPEN, CLOSE, SlotStatus.FREE);
            }
            for (DemoFixtures.BedSeed b : DemoFixtures.beds()) {
                seedBedSlots(b.bedId(), date, OPEN, CLOSE, SlotStatus.FREE);
            }
        }

        seedTherapistSlots(t1, anchor, 56, 64, SlotStatus.REST);
        seedTherapistSlots(t1, anchor, 78, 83, SlotStatus.LOCKED);
        seedOccupancy(ResourceType.THERAPIST, t1, anchor, 78, 83);

        seedTherapistSlots(t3, anchor, 40, 44, SlotStatus.BOOKED);
        seedTherapistSlots(t3, anchor, 44, 45, SlotStatus.BUFFER);
        seedOccupancy(ResourceType.THERAPIST, t3, anchor, 40, 45);

        seedBedSlots(BED1, anchor, 78, 83, SlotStatus.LOCKED);
        seedOccupancy(ResourceType.BED, BED1, anchor, 78, 83);

        seedBedSlots(BED2, anchor, 40, 44, SlotStatus.BOOKED);
        seedBedSlots(BED2, anchor, 44, 45, SlotStatus.BUFFER);
        seedOccupancy(ResourceType.BED, BED2, anchor, 40, 45);
    }

    @Override
    public List<TherapistSlotView> listTherapistSlots(long storeId, LocalDate date) {
        List<TherapistSlotView> out = new ArrayList<>();
        for (Map.Entry<String, TherapistSlotView> e : therapistSlots.entrySet()) {
            if (e.getKey().contains("|" + date + "|") && ownsTherapist(storeId, e.getValue().therapistId())) {
                TherapistSlotView v = e.getValue();
                String live = liveOccupancy == null
                        ? null
                        : liveOccupancy.therapistSlotStatus(v.therapistId(), date, v.slotNo());
                out.add(isBusy(live)
                        ? new TherapistSlotView(v.therapistId(), v.slotNo(), live, v.priceOverrideFen())
                        : v);
            }
        }
        out.sort(Comparator.comparingLong(TherapistSlotView::therapistId).thenComparingInt(TherapistSlotView::slotNo));
        return out;
    }

    @Override
    public List<BedSlotView> listBedSlots(long storeId, LocalDate date) {
        List<BedSlotView> out = new ArrayList<>();
        for (Map.Entry<String, BedSlotView> e : bedSlots.entrySet()) {
            if (e.getKey().contains("|" + date + "|") && ownsBed(storeId, e.getValue().bedId())) {
                BedSlotView v = e.getValue();
                String live = liveOccupancy == null
                        ? null
                        : liveOccupancy.bedSlotStatus(v.bedId(), date, v.slotNo());
                out.add(isBusy(live) ? new BedSlotView(v.bedId(), v.slotNo(), live) : v);
            }
        }
        out.sort(Comparator.comparingLong(BedSlotView::bedId).thenComparingInt(BedSlotView::slotNo));
        return out;
    }

    @Override
    public List<OccupancyView> listOccupancies(long storeId, LocalDate date) {
        List<OccupancyView> out = new ArrayList<>();
        for (Map.Entry<String, OccupancyView> e : occupancies.entrySet()) {
            if (e.getKey().contains("|" + date + "|")) {
                out.add(e.getValue());
            }
        }
        if (liveOccupancy != null) {
            // Method call, not field access: liveOccupancy is a CGLIB proxy.
            for (SlotOccupyStore.OccupancyInsert row : liveOccupancy.occupanciesOn(date)) {
                out.add(new OccupancyView(row.resourceType(), row.resourceId(), row.slotNo()));
            }
        }
        // AvailabilityDay keeps these in a Set, so overlap with the seed is harmless.
        return out;
    }

    /**
     * Slot keys carry no store, so these listings would otherwise return every
     * therapist and bed regardless of the store asked for — harmless with one
     * store, wrong with two. The JDBC side filters by store_id in SQL.
     */
    private static boolean ownsTherapist(long storeId, long therapistId) {
        return DemoFixtures.therapists().stream()
                .filter(s -> s.therapistId() == therapistId)
                .findFirst()
                .map(s -> s.storeId() == storeId)
                .orElse(true);
    }

    private static boolean ownsBed(long storeId, long bedId) {
        return DemoFixtures.beds().stream()
                .filter(b -> b.bedId() == bedId)
                .findFirst()
                .map(b -> b.storeId() == storeId)
                .orElse(true);
    }

    /** Only a non-FREE live status overrides the seed, so a paid booking reads BOOKED. */
    private static boolean isBusy(String status) {
        return status != null && !SlotStatus.FREE.equals(status);
    }

    static String tkey(long therapistId, LocalDate date, int slotNo) {
        return "T|" + therapistId + "|" + date + "|" + slotNo;
    }

    static String bkey(long bedId, LocalDate date, int slotNo) {
        return "B|" + bedId + "|" + date + "|" + slotNo;
    }

    static String okey(String type, long resourceId, LocalDate date, int slotNo) {
        return type + "|" + resourceId + "|" + date + "|" + slotNo;
    }
}
