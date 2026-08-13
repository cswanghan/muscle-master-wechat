package com.jisuodashi.inventory;

import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.inventory.SlotGenerateStore.BedRef;
import com.jisuodashi.inventory.SlotGenerateStore.BedSlotInsert;
import com.jisuodashi.inventory.SlotGenerateStore.ExistingTherapistSlot;
import com.jisuodashi.inventory.SlotGenerateStore.HumanTaskInsert;
import com.jisuodashi.inventory.SlotGenerateStore.StoreRef;
import com.jisuodashi.inventory.SlotGenerateStore.TherapistRef;
import com.jisuodashi.inventory.SlotGenerateStore.TherapistSlotInsert;
import com.jisuodashi.inventory.TherapistDayPlan.PlannedSlot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Writes therapist_slot / bed_slot as FREE or REST. Never occupancy, BUFFER, or lockNew.
 */
@Service
public class SlotGenerateService {

    public static final int HORIZON_DAYS = 15;
    public static final String TASK_STORE_CONFLICT = "GENERATION_STORE_CONFLICT";

    private final SlotGenerateStore store;
    private final SlotPlanner planner;
    private final LongSupplier ids;
    private final AppClock clock;

    @Autowired
    public SlotGenerateService(SlotGenerateStore store, SnowflakeIdGenerator ids, AppClock clock) {
        this(store, ids::nextId, clock);
    }

    public SlotGenerateService(SlotGenerateStore store, LongSupplier ids, AppClock clock) {
        this.store = store;
        this.planner = new SlotPlanner();
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional
    public SlotGenerateResult generate() {
        return generate(clock.today());
    }

    @Transactional
    public SlotGenerateResult generate(LocalDate today) {
        LocalDate horizon = today.plusDays(HORIZON_DAYS);
        boolean firstRun = !store.existsTherapistSlotInRange(today, horizon);

        Map<Long, StoreRef> stores = store.listActiveStores().stream()
                .collect(Collectors.toMap(StoreRef::id, s -> s, (a, b) -> a, LinkedHashMap::new));
        List<TherapistRef> therapists = store.listActiveTherapists();

        Counters c = new Counters();
        List<SlotGenerateResult.SampleDay> samples = new ArrayList<>();
        for (LocalDate date = today; !date.isAfter(horizon); date = date.plusDays(1)) {
            for (TherapistRef therapist : therapists) {
                TherapistDayPlan plan = generateTherapistDay(therapist, date, stores, c);
                if (samples.size() < 8 && !plan.slots().isEmpty()) {
                    samples.add(toSample(therapist, plan));
                }
            }
            generateBedDay(date, stores, c);
        }
        return new SlotGenerateResult(
                today, today, horizon, firstRun,
                c.therapistInserted, c.therapistIgnored,
                c.bedInserted, c.bedIgnored,
                c.restWritten, c.freeWritten,
                c.conflicts, c.humanTasks,
                List.copyOf(samples)
        );
    }

    private TherapistDayPlan generateTherapistDay(
            TherapistRef therapist,
            LocalDate date,
            Map<Long, StoreRef> stores,
            Counters c
    ) {
        int weekday = date.getDayOfWeek().getValue();
        var templates = store.listActiveTemplates(therapist.id(), weekday, date).stream()
                .filter(t -> stores.containsKey(t.storeId()))
                .toList();
        var exceptions = store.listApprovedExceptions(therapist.id(), date);
        TherapistDayPlan plan = planner.plan(therapist.id(), date, templates, exceptions);
        Map<Integer, ExistingTherapistSlot> existing = store.listTherapistSlots(therapist.id(), date);

        for (Map.Entry<Integer, Set<Long>> conflict : plan.conflicts().entrySet()) {
            raiseConflict(therapist.id(), date, conflict.getKey(), conflict.getValue(),
                    firstStore(conflict.getValue()), c);
        }

        for (Map.Entry<Integer, PlannedSlot> e : plan.slots().entrySet()) {
            int slotNo = e.getKey();
            PlannedSlot planned = e.getValue();
            ExistingTherapistSlot have = existing.get(slotNo);
            if (have != null) {
                if (have.storeId() != planned.storeId()) {
                    raiseConflict(therapist.id(), date, slotNo,
                            Set.of(have.storeId(), planned.storeId()), planned.storeId(), c);
                } else {
                    c.therapistIgnored++;
                }
                continue;
            }
            boolean inserted = store.insertTherapistSlotIgnore(new TherapistSlotInsert(
                    ids.getAsLong(), therapist.id(), planned.storeId(), date, slotNo, planned.status()));
            if (inserted) {
                c.therapistInserted++;
                if (SlotStatus.REST.equals(planned.status())) {
                    c.restWritten++;
                } else {
                    c.freeWritten++;
                }
            } else {
                c.therapistIgnored++;
            }
        }
        return plan;
    }

    private void generateBedDay(LocalDate date, Map<Long, StoreRef> stores, Counters c) {
        for (BedRef bed : store.listActiveBeds()) {
            StoreRef storeRef = stores.get(bed.storeId());
            if (storeRef == null) {
                continue;
            }
            for (int slotNo : SlotTimes.range(storeRef.businessStart(), storeRef.businessEnd())) {
                boolean inserted = store.insertBedSlotIgnore(new BedSlotInsert(
                        ids.getAsLong(), bed.id(), bed.storeId(), date, slotNo, SlotStatus.FREE));
                if (inserted) {
                    c.bedInserted++;
                } else {
                    c.bedIgnored++;
                }
            }
        }
    }

    private void raiseConflict(
            long therapistId,
            LocalDate date,
            int slotNo,
            Set<Long> storeIds,
            Long taskStoreId,
            Counters c
    ) {
        c.conflicts++;
        String storesCsv = storeIds.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
        String bizKey = "gsc:" + therapistId + ":" + date + ":" + slotNo;
        String detail = "{\"therapistId\":" + therapistId
                + ",\"slotDate\":\"" + date + "\""
                + ",\"slotNo\":" + slotNo
                + ",\"storeIds\":[" + storesCsv + "]}";
        store.insertHumanTaskIgnore(new HumanTaskInsert(
                ids.getAsLong(),
                TASK_STORE_CONFLICT,
                bizKey,
                "排班生成门店冲突 " + date + " slot=" + slotNo,
                detail,
                taskStoreId
        ));
        c.humanTasks++;
    }

    private static Long firstStore(Set<Long> storeIds) {
        return storeIds.stream().min(Long::compareTo).orElse(null);
    }

    private static SlotGenerateResult.SampleDay toSample(TherapistRef therapist, TherapistDayPlan plan) {
        int free = 0;
        int rest = 0;
        long storeId = 0;
        for (PlannedSlot slot : plan.slots().values()) {
            if (SlotStatus.REST.equals(slot.status())) {
                rest++;
            } else {
                free++;
            }
            storeId = slot.storeId();
        }
        return new SlotGenerateResult.SampleDay(therapist.id(), "", plan.date(), free, rest, storeId);
    }

    private static final class Counters {
        int therapistInserted;
        int therapistIgnored;
        int bedInserted;
        int bedIgnored;
        int restWritten;
        int freeWritten;
        int conflicts;
        int humanTasks;
    }
}
