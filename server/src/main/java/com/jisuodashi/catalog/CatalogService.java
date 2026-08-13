package com.jisuodashi.catalog;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.ClockConfig;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.PhoneCrypto;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class CatalogService {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final String EMPTY_HINT = "面诊后调整";

    private final CatalogRepository catalog;
    private final PhoneCrypto phoneCrypto;
    private final Clock clock;
    private final int nearMeters;
    private final TtlCache<String, List<CatalogDtos.StoreListItem>> storeCache;

    public CatalogService(
            CatalogRepository catalog,
            PhoneCrypto phoneCrypto,
            Clock clock,
            AppProperties properties) {
        this.catalog = catalog;
        this.phoneCrypto = phoneCrypto;
        this.clock = clock;
        this.nearMeters = properties.getCatalog().getNearMeters();
        this.storeCache = new TtlCache<>(properties.getCatalog().getStoreCacheTtl(), clock);
    }

    public CatalogDtos.Page<CatalogDtos.StoreListItem> listStores(
            Double lng, Double lat, String cursor, Integer limit) {
        if ((lng == null) != (lat == null)) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "lng/lat 须成对出现");
        }
        int size = normalizeLimit(limit);
        Long afterId = parseCursor(cursor);
        String cacheKey = (lng == null)
                ? "all"
                : "geo:" + Math.round(lng * 1000) + ":" + Math.round(lat * 1000);
        List<CatalogDtos.StoreListItem> all = storeCache.get(cacheKey, () -> buildStoreList(lng, lat));
        List<CatalogDtos.StoreListItem> sliced = new ArrayList<>();
        boolean skipping = afterId != null;
        String next = null;
        for (CatalogDtos.StoreListItem item : all) {
            long id = Long.parseLong(item.storeId());
            if (skipping) {
                if (id == afterId) {
                    skipping = false;
                }
                continue;
            }
            if (sliced.size() == size) {
                next = item.storeId();
                break;
            }
            sliced.add(item);
        }
        return new CatalogDtos.Page<>(sliced, next);
    }

    public CatalogDtos.StoreDetail getStore(long id) {
        CatalogModels.Store store = catalog.findStore(id)
                .filter(s -> s.status() == 1)
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "门店不存在"));
        List<CatalogDtos.ProjectSummary> projects = listedProjects(store.id(), null);
        return new CatalogDtos.StoreDetail(
                String.valueOf(store.id()),
                store.code(),
                store.name(),
                decryptAddress(store),
                store.lng(),
                store.lat(),
                store.businessStart().format(HM),
                store.businessEnd().format(HM),
                isOpen(store),
                projects);
    }

    public CatalogDtos.Page<CatalogDtos.TherapistItem> listTherapists(
            Long storeId, Long symptomId, String cursor, Integer limit) {
        int size = normalizeLimit(limit);
        Long afterId = parseCursor(cursor);
        LocalDate today = LocalDate.now(clock.withZone(ClockConfig.SHANGHAI));
        int weekday = isoWeekday(today.getDayOfWeek());
        Set<Long> onDuty = onDutyTherapistIds(storeId, today, weekday);
        List<CatalogDtos.TherapistItem> items = new ArrayList<>();
        String next = null;
        boolean skipping = afterId != null;
        for (CatalogModels.Therapist t : catalog.listTherapists().stream()
                .filter(th -> th.status() == 1)
                .sorted(Comparator.comparingLong(CatalogModels.Therapist::id))
                .toList()) {
            if (!onDuty.contains(t.id())) {
                continue;
            }
            if (storeId != null && t.homeStoreId() != storeId && !onDutyAtStore(t.id(), storeId, today, weekday)) {
                continue;
            }
            if (symptomId != null && !t.symptomIds().contains(symptomId)) {
                continue;
            }
            if (skipping) {
                if (t.id() == afterId) {
                    skipping = false;
                }
                continue;
            }
            if (items.size() == size) {
                next = String.valueOf(t.id());
                break;
            }
            items.add(new CatalogDtos.TherapistItem(
                    String.valueOf(t.id()),
                    t.name(),
                    t.employeeNo(),
                    t.level(),
                    t.ratingX100(),
                    t.intro(),
                    t.avatarUrl(),
                    String.valueOf(t.homeStoreId())));
        }
        return new CatalogDtos.Page<>(items, next);
    }

    public CatalogDtos.Page<CatalogDtos.ProjectSummary> listProjects(Long storeId, Long symptomId) {
        return CatalogDtos.Page.of(listedProjects(storeId, symptomId));
    }

    public CatalogDtos.Page<CatalogDtos.SymptomItem> listSymptoms() {
        List<CatalogDtos.SymptomItem> items = catalog.listSymptoms().stream()
                .filter(s -> s.status() == 1)
                .sorted(Comparator.comparingInt(CatalogModels.Symptom::sortNo).thenComparingLong(CatalogModels.Symptom::id))
                .map(s -> new CatalogDtos.SymptomItem(
                        String.valueOf(s.id()),
                        s.parentId() == null ? null : String.valueOf(s.parentId()),
                        s.type(),
                        s.name()))
                .toList();
        return CatalogDtos.Page.of(items);
    }

    public CatalogDtos.SymptomProjects projectsForSymptom(long symptomId) {
        CatalogModels.Symptom symptom = catalog.findSymptom(symptomId)
                .filter(s -> s.status() == 1)
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "症状不存在"));
        List<CatalogDtos.ProjectSummary> items = listedProjects(null, symptom.id());
        return new CatalogDtos.SymptomProjects(items, items.isEmpty() ? EMPTY_HINT : null);
    }

    private List<CatalogDtos.StoreListItem> buildStoreList(Double lng, Double lat) {
        List<CatalogDtos.StoreListItem> items = new ArrayList<>();
        for (CatalogModels.Store store : catalog.listStores()) {
            if (store.status() != 1) {
                continue;
            }
            Integer distance = null;
            boolean near = false;
            if (lng != null && store.lng() != null && store.lat() != null) {
                distance = Geo.distanceMeters(
                        lat, lng, store.lat().doubleValue(), store.lng().doubleValue());
                near = distance <= nearMeters;
            }
            items.add(new CatalogDtos.StoreListItem(
                    String.valueOf(store.id()),
                    store.name(),
                    distance,
                    near,
                    store.businessStart().format(HM),
                    store.businessEnd().format(HM),
                    isOpen(store)));
        }
        if (lng != null) {
            items.sort(Comparator
                    .comparing((CatalogDtos.StoreListItem s) -> s.distanceM() == null ? Integer.MAX_VALUE : s.distanceM())
                    .thenComparing(s -> Long.parseLong(s.storeId())));
        } else {
            items.sort(Comparator.comparing(s -> Long.parseLong(s.storeId())));
        }
        return items;
    }

    private List<CatalogDtos.ProjectSummary> listedProjects(Long storeId, Long symptomId) {
        Set<Long> allowedByStore = null;
        if (storeId != null) {
            allowedByStore = new HashSet<>();
            for (CatalogModels.StoreProject sp : catalog.listStoreProjects()) {
                if (sp.storeId() == storeId && sp.status() == 1) {
                    allowedByStore.add(sp.projectId());
                }
            }
        }
        Set<Long> allowedBySymptom = null;
        if (symptomId != null) {
            allowedBySymptom = new HashSet<>();
            for (CatalogModels.SymptomProject sp : catalog.listSymptomProjects()) {
                if (sp.symptomId() == symptomId) {
                    allowedBySymptom.add(sp.projectId());
                }
            }
        }
        List<CatalogDtos.ProjectSummary> out = new ArrayList<>();
        for (CatalogModels.Project p : catalog.listProjects()) {
            if (p.status() != 1) {
                continue;
            }
            if (allowedByStore != null && !allowedByStore.contains(p.id())) {
                continue;
            }
            if (allowedBySymptom != null && !allowedBySymptom.contains(p.id())) {
                continue;
            }
            out.add(new CatalogDtos.ProjectSummary(
                    String.valueOf(p.id()),
                    p.name(),
                    p.durationMinutes(),
                    p.bufferMinutes(),
                    Pricing.priceFen(storeId, p.id(), p, catalog.listStoreProjects()),
                    p.description(),
                    p.coverUrl()));
        }
        return out;
    }

    private Set<Long> onDutyTherapistIds(Long storeId, LocalDate today, int weekday) {
        Set<Long> ids = new HashSet<>();
        for (CatalogModels.ScheduleTemplate tpl : catalog.listTemplates()) {
            if (tpl.status() != 1 || tpl.weekday() != weekday) {
                continue;
            }
            if (storeId != null && tpl.storeId() != storeId) {
                continue;
            }
            if (tpl.effectiveFrom() != null && today.isBefore(tpl.effectiveFrom())) {
                continue;
            }
            if (tpl.effectiveTo() != null && today.isAfter(tpl.effectiveTo())) {
                continue;
            }
            ids.add(tpl.therapistId());
        }
        return ids;
    }

    private boolean onDutyAtStore(long therapistId, long storeId, LocalDate today, int weekday) {
        return onDutyTherapistIds(storeId, today, weekday).contains(therapistId);
    }

    private boolean isOpen(CatalogModels.Store store) {
        if (store.status() != 1) {
            return false;
        }
        ZoneId zone = store.timezone() == null || store.timezone().isBlank()
                ? ClockConfig.SHANGHAI
                : ZoneId.of(store.timezone());
        LocalTime now = LocalTime.now(clock.withZone(zone));
        return !now.isBefore(store.businessStart()) && now.isBefore(store.businessEnd());
    }

    private String decryptAddress(CatalogModels.Store store) {
        if (store.addressCipher() == null) {
            return null;
        }
        return phoneCrypto.decrypt(store.addressCipher());
    }

    private static int normalizeLimit(Integer limit) {
        int size = limit == null ? 20 : limit;
        if (size < 1 || size > 100) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "limit 须为 1–100");
        }
        return size;
    }

    private static Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(cursor);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "cursor 无效");
        }
    }

    private static int isoWeekday(DayOfWeek day) {
        return day.getValue();
    }
}
