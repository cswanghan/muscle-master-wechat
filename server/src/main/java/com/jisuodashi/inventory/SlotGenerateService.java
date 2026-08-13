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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Writes therapist_slot / bed_slot as FREE or REST. Never occupancy, BUFFER, or lockNew.
 * Each therapist-day / bed-day commits on its own so one bad row does not roll back the horizon.
 */
@Service
public class SlotGenerateService {

    public static final int HORIZON_DAYS = 15;
    public static final String TASK_STORE_CONFLICT = "GENERATION_STORE_CONFLICT";
    public static final String TASK_GENERATION_FAILED = "GENERATION_FAILED";

    private static final Logger log = LoggerFactory.getLogger(SlotGenerateService.class);

    private final SlotGenerateStore store;
    private final SlotPlanner planner;
    private final LongSupplier ids;
    private final AppClock clock;
    private final TransactionTemplate dayTx;

    @Autowired
    public SlotGenerateService(
            SlotGenerateStore store,
            SnowflakeIdGenerator ids,
            AppClock clock,
            PlatformTransactionManager txManager
    ) {
        this(store, ids::nextId, clock, requiresNew(txManager));
    }

    public SlotGenerateService(SlotGenerateStore store, LongSupplier ids, AppClock clock) {
        this(store, ids, clock, null);
    }

    SlotGenerateService(SlotGenerateStore store, LongSupplier ids, AppClock clock, TransactionTemplate dayTx) {
        this.store = store;
        this.planner = new SlotPlanner();
        this.ids = ids;
        this.clock = clock;
        this.dayTx = dayTx;
    }

    public SlotGenerateResult generate() {
        return generate(clock.today());
    }

    public SlotGenerateResult generate(LocalDate today) {
        LocalDate horizon = today.plusDays(HORIZON_DAYS);
        boolean firstRun = !store.existsTherapistSlotInRange(today, horizon);

        Map<Long, StoreRef> stores = store.listActiveStores().stream()
                .collect(Collectors.toMap(StoreRef::id, s -> s, (a, b) -> a, LinkedHashMap::new));
        List<TherapistRef> therapists = store.listActiveTherapists();

        Counters c = new Counters();
        List<SlotGenerateResult.SampleDay> samples = new ArrayList<>();
        for (LocalDate date = today; !date.isAfter(horizon); date = date.plusDays(1)) {
            LocalDate day = date;
            for (TherapistRef therapist : therapists) {
                try {
                    TherapistDayPlan plan = inNewTx(() -> generateTherapistDay(therapist, day, stores, c));
                    if (samples.size() < 8 && plan != null && !plan.slots().isEmpty()) {
                        samples.add(toSample(therapist, plan));
                    }
                } catch (RuntimeException ex) {
                    log.warn("slot generate failed therapist={} date={}", therapist.id(), day, ex);
                    recordFailure(therapist.id(), day, "therapist", ex, therapist.homeStoreId(), c);
                }
            }
            for (BedRef bed : store.listActiveBeds()) {
                try {
                    inNewTx(() -> {
                        generateOneBed(bed, day, stores, c);
                        return null;
                    });
                } catch (RuntimeException ex) {
                    log.warn("slot generate failed bed={} date={}", bed.id(), day, ex);
                    recordFailure(null, day, "bed:" + bed.id(), ex, bed.storeId(), c);
                }
            }
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
        StoreRef home = stores.get(therapist.homeStoreId());
        LocalTime bizStart = home != null ? home.businessStart() : null;
        LocalTime bizEnd = home != null ? home.businessEnd() : null;
        TherapistDayPlan plan = planner.plan(therapist.id(), date, templates, exceptions, bizStart, bizEnd);
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

    private void generateOneBed(BedRef bed, LocalDate date, Map<Long, StoreRef> stores, Counters c) {
        StoreRef storeRef = stores.get(bed.storeId());
        if (storeRef == null) {
            return;
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

    private void recordFailure(
            Long therapistId,
            LocalDate date,
            String scope,
            Exception ex,
            Long storeId,
            Counters c
    ) {
        try {
            inNewTx(() -> {
                raiseFailure(therapistId, date, scope, ex, storeId, c);
                return null;
            });
        } catch (RuntimeException taskEx) {
            log.error("failed to record GENERATION_FAILED scope={} date={}", scope, date, taskEx);
        }
    }

    private void raiseFailure(
            Long therapistId,
            LocalDate date,
            String scope,
            Exception ex,
            Long storeId,
            Counters c
    ) {
        String bizKey = therapistId != null
                ? "gf:" + therapistId + ":" + date
                : "gf:" + scope + ":" + date;
        String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        String detail = "{\"scope\":\"" + jsonEscape(scope) + "\""
                + ",\"slotDate\":\"" + date + "\""
                + (therapistId != null ? ",\"therapistId\":" + therapistId : "")
                + ",\"error\":\"" + jsonEscape(msg) + "\"}";
        store.insertHumanTaskIgnore(new HumanTaskInsert(
                ids.getAsLong(),
                TASK_GENERATION_FAILED,
                bizKey,
                "排班生成失败 " + scope + " " + date,
                detail,
                storeId
        ));
        c.humanTasks++;
    }

    private <T> T inNewTx(Supplier<T> work) {
        if (dayTx == null) {
            return work.get();
        }
        return dayTx.execute(status -> work.get());
    }

    private static TransactionTemplate requiresNew(PlatformTransactionManager txManager) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }

    private static Long firstStore(Set<Long> storeIds) {
        return storeIds.stream().min(Long::compareTo).orElse(null);
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
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
