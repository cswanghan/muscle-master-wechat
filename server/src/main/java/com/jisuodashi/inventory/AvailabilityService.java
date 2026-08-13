package com.jisuodashi.inventory;

import com.jisuodashi.catalog.CatalogModels;
import com.jisuodashi.catalog.CatalogRepository;
import com.jisuodashi.catalog.Pricing;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Bookable starts only: N consecutive therapist slots idle and some bed window idle.
 * {@code starts[]} never lists LOCKED. Price is D13.
 */
@Service
public class AvailabilityService {

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final AvailabilityStore store;
    private final CatalogRepository catalog;
    private final AvailabilityCache cache;

    public AvailabilityService(
            AvailabilityStore store,
            CatalogRepository catalog,
            AvailabilityCache cache
    ) {
        this.store = store;
        this.catalog = catalog;
        this.cache = cache;
    }

    public AvailabilityDtos.Availability query(
            long storeId,
            LocalDate date,
            long projectId,
            Long therapistId,
            boolean includeBusy
    ) {
        catalog.findStore(storeId)
                .filter(s -> s.status() == 1)
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "门店不存在"));
        CatalogModels.Project project = catalog.findProject(projectId)
                .filter(p -> p.status() == 1)
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "项目不存在"));

        OccupySpec spec = OccupySpec.of(project.durationMinutes(), project.bufferMinutes());
        Long storeFen = storeProjectFen(storeId, projectId);
        AvailabilityDay day = cache.get(storeId, date, () -> loadDay(storeId, date));

        List<AvailabilityDtos.Therapist> therapists = new ArrayList<>();
        for (CatalogModels.Therapist card : catalog.listTherapists().stream()
                .filter(t -> t.status() == 1)
                .sorted(Comparator.comparingLong(CatalogModels.Therapist::id))
                .toList()) {
            if (therapistId != null && card.id() != therapistId) {
                continue;
            }
            TreeMap<Integer, AvailabilityDay.Cell> cells = day.therapistCells(card.id());
            if (cells.isEmpty()) {
                continue;
            }
            List<AvailabilityDtos.Start> starts = bookableStarts(day, card.id(), cells, spec, storeFen, project);
            List<AvailabilityDtos.Block> blocks = includeBusy ? blocks(day, card.id(), cells) : null;
            if (starts.isEmpty() && blocks == null) {
                continue;
            }
            therapists.add(new AvailabilityDtos.Therapist(
                    String.valueOf(card.id()),
                    card.name(),
                    card.level(),
                    card.ratingX100(),
                    starts,
                    blocks));
        }

        return new AvailabilityDtos.Availability(
                String.valueOf(storeId),
                date.format(ISO_DATE),
                String.valueOf(projectId),
                OccupySpec.SLOT_MINUTES,
                spec.slotCount(),
                List.copyOf(therapists));
    }

    /** lockNew / ReleaseLock / leave / pay all share this store+date key. */
    public void invalidate(long storeId, LocalDate date) {
        cache.invalidate(storeId, date);
    }

    private AvailabilityDay loadDay(long storeId, LocalDate date) {
        return new AvailabilityDay(
                storeId,
                date,
                store.listTherapistSlots(storeId, date),
                store.listBedSlots(storeId, date),
                store.listOccupancies(storeId, date));
    }

    private List<AvailabilityDtos.Start> bookableStarts(
            AvailabilityDay day,
            long therapistId,
            Map<Integer, AvailabilityDay.Cell> cells,
            OccupySpec spec,
            Long storeFen,
            CatalogModels.Project project
    ) {
        List<AvailabilityDtos.Start> starts = new ArrayList<>();
        for (int slotNo : cells.keySet()) {
            if (!day.windowTherapistIdle(therapistId, slotNo, spec.slotCount())) {
                continue;
            }
            if (!day.someBedWindowIdle(slotNo, spec.slotCount())) {
                continue;
            }
            AvailabilityDay.Cell startCell = cells.get(slotNo);
            long priceFen = Pricing.priceFen(startCell.priceOverrideFen(), storeFen, project.priceFen());
            starts.add(new AvailabilityDtos.Start(
                    slotNo,
                    SlotTimes.toTime(slotNo).format(HM),
                    priceFen));
        }
        return List.copyOf(starts);
    }

    private List<AvailabilityDtos.Block> blocks(
            AvailabilityDay day,
            long therapistId,
            TreeMap<Integer, AvailabilityDay.Cell> cells
    ) {
        List<AvailabilityDtos.Block> out = new ArrayList<>();
        for (AvailabilityDay.Cell cell : cells.values()) {
            boolean occupied = day.occupied(ResourceType.THERAPIST, therapistId, cell.slotNo());
            out.add(new AvailabilityDtos.Block(
                    cell.slotNo(),
                    SlotTimes.toTime(cell.slotNo()).format(HM),
                    AvailabilityDay.blockState(cell.status(), occupied)));
        }
        return List.copyOf(out);
    }

    private Long storeProjectFen(long storeId, long projectId) {
        for (CatalogModels.StoreProject sp : catalog.listStoreProjects()) {
            if (sp.storeId() == storeId && sp.projectId() == projectId && sp.status() == 1) {
                return sp.priceFen();
            }
        }
        return null;
    }
}
