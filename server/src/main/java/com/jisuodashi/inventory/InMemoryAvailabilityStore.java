package com.jisuodashi.inventory;

import com.jisuodashi.catalog.DemoCatalogIds;
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

    static final LocalDate DEMO_DATE = LocalDate.of(2026, 8, 14);
    static final int OPEN = 40;
    static final int CLOSE = 88;
    static final long BED1 = 3_100_000_000_000_000_201L;
    static final long BED2 = 3_100_000_000_000_000_202L;

    private final Map<String, TherapistSlotView> therapistSlots = new ConcurrentHashMap<>();
    private final Map<String, BedSlotView> bedSlots = new ConcurrentHashMap<>();
    private final Map<String, OccupancyView> occupancies = new ConcurrentHashMap<>();

    public InMemoryAvailabilityStore() {
        seedFourStatesDemo();
    }

    /** Tests that want an empty calendar. */
    public static InMemoryAvailabilityStore blank() {
        return new InMemoryAvailabilityStore(false);
    }

    private InMemoryAvailabilityStore(boolean unused) {
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

    /** 林晓 REST+LOCKED, 周可 BOOKED, 陈默 FREE — four colors, starts only on FREE. */
    public final void seedFourStatesDemo() {
        therapistSlots.clear();
        bedSlots.clear();
        occupancies.clear();
        long t1 = DemoCatalogIds.THERAPIST_LIN;
        long t2 = DemoCatalogIds.THERAPIST_CHEN;
        long t3 = DemoCatalogIds.THERAPIST_ZHOU;
        seedTherapistSlots(t1, DEMO_DATE, OPEN, CLOSE, SlotStatus.FREE);
        seedTherapistSlots(t1, DEMO_DATE, 56, 64, SlotStatus.REST);
        seedTherapistSlots(t1, DEMO_DATE, 78, 83, SlotStatus.LOCKED);
        seedOccupancy(ResourceType.THERAPIST, t1, DEMO_DATE, 78, 83);

        seedTherapistSlots(t2, DEMO_DATE, OPEN, CLOSE, SlotStatus.FREE);

        seedTherapistSlots(t3, DEMO_DATE, OPEN, CLOSE, SlotStatus.FREE);
        seedTherapistSlots(t3, DEMO_DATE, 40, 45, SlotStatus.BOOKED);
        seedOccupancy(ResourceType.THERAPIST, t3, DEMO_DATE, 40, 45);

        seedBedSlots(BED1, DEMO_DATE, OPEN, CLOSE, SlotStatus.FREE);
        seedBedSlots(BED1, DEMO_DATE, 78, 83, SlotStatus.LOCKED);
        seedOccupancy(ResourceType.BED, BED1, DEMO_DATE, 78, 83);

        seedBedSlots(BED2, DEMO_DATE, OPEN, CLOSE, SlotStatus.FREE);
        seedBedSlots(BED2, DEMO_DATE, 40, 45, SlotStatus.BOOKED);
        seedOccupancy(ResourceType.BED, BED2, DEMO_DATE, 40, 45);
    }

    @Override
    public List<TherapistSlotView> listTherapistSlots(long storeId, LocalDate date) {
        List<TherapistSlotView> out = new ArrayList<>();
        for (Map.Entry<String, TherapistSlotView> e : therapistSlots.entrySet()) {
            if (e.getKey().contains("|" + date + "|")) {
                out.add(e.getValue());
            }
        }
        out.sort(Comparator.comparingLong(TherapistSlotView::therapistId).thenComparingInt(TherapistSlotView::slotNo));
        return out;
    }

    @Override
    public List<BedSlotView> listBedSlots(long storeId, LocalDate date) {
        List<BedSlotView> out = new ArrayList<>();
        for (Map.Entry<String, BedSlotView> e : bedSlots.entrySet()) {
            if (e.getKey().contains("|" + date + "|")) {
                out.add(e.getValue());
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
        return out;
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
