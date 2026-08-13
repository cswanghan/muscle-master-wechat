package com.jisuodashi.catalog;

import com.jisuodashi.auth.DemoStaffIds;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** H2 cannot apply V1; load the same IDs as V3__demo_store.sql. */
@Repository
@Profile("dev")
public class InMemoryCatalogRepository implements CatalogRepository {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalTime TEN = LocalTime.of(10, 0);
    private static final LocalTime TWENTY_TWO = LocalTime.of(22, 0);

    private final List<CatalogModels.Store> stores;
    private final List<CatalogModels.Therapist> therapists;
    private final List<CatalogModels.Project> projects;
    private final List<CatalogModels.StoreProject> storeProjects;
    private final List<CatalogModels.Symptom> symptoms;
    private final List<CatalogModels.SymptomProject> symptomProjects;
    private final List<CatalogModels.ScheduleTemplate> templates;

    public InMemoryCatalogRepository() {
        stores = List.of(new CatalogModels.Store(
                DemoCatalogIds.STORE,
                "DEMO01",
                "肌松大师·演示旗舰店",
                null,
                null,
                new BigDecimal("121.4737000"),
                new BigDecimal("31.2304000"),
                TEN,
                TWENTY_TWO,
                "Asia/Shanghai",
                1));

        List<Long> allProjects = List.of(
                DemoCatalogIds.PROJECT_P60, DemoCatalogIds.PROJECT_P45, DemoCatalogIds.PROJECT_P90);
        therapists = List.of(
                new CatalogModels.Therapist(
                        DemoCatalogIds.THERAPIST_LIN, DemoStaffIds.T1, "T001", "林晓",
                        DemoCatalogIds.STORE, "SENIOR", null, "首席技师，肩颈深层", 490, 1,
                        allProjects, List.of(DemoCatalogIds.SYMPTOM_NECK, DemoCatalogIds.SYMPTOM_SORE)),
                new CatalogModels.Therapist(
                        DemoCatalogIds.THERAPIST_CHEN, DemoStaffIds.T2, "T002", "陈默",
                        DemoCatalogIds.STORE, "MIDDLE", null, "腰背理筋", 480, 1,
                        allProjects, List.of(DemoCatalogIds.SYMPTOM_BACK, DemoCatalogIds.SYMPTOM_SORE)),
                new CatalogModels.Therapist(
                        DemoCatalogIds.THERAPIST_ZHOU, DemoStaffIds.T3, "T003", "周可",
                        DemoCatalogIds.STORE, "JUNIOR", null, "全身放松", 470, 1,
                        allProjects, List.of(
                                DemoCatalogIds.SYMPTOM_NECK, DemoCatalogIds.SYMPTOM_BACK, DemoCatalogIds.SYMPTOM_SORE)));

        projects = List.of(
                new CatalogModels.Project(
                        DemoCatalogIds.PROJECT_P60, "P60", "全身推拿放松", 60, 15, 19800,
                        "全身推拿 60 分钟，缓冲 15 分钟", null, 1),
                new CatalogModels.Project(
                        DemoCatalogIds.PROJECT_P45, "P45", "肩颈专项疏通", 45, 15, 12800,
                        "肩颈专项 45 分钟，缓冲 15 分钟", null, 1),
                new CatalogModels.Project(
                        DemoCatalogIds.PROJECT_P90, "P90", "腰背深层理筋", 90, 15, 26800,
                        "腰背理筋 90 分钟，缓冲 15 分钟", null, 1));

        storeProjects = List.of(
                new CatalogModels.StoreProject(DemoCatalogIds.STORE, DemoCatalogIds.PROJECT_P60, null, 1),
                new CatalogModels.StoreProject(DemoCatalogIds.STORE, DemoCatalogIds.PROJECT_P45, null, 1),
                new CatalogModels.StoreProject(DemoCatalogIds.STORE, DemoCatalogIds.PROJECT_P90, null, 1));

        symptoms = List.of(
                new CatalogModels.Symptom(DemoCatalogIds.SYMPTOM_NECK, null, "BODY_PART", "肩颈", 1, 1),
                new CatalogModels.Symptom(DemoCatalogIds.SYMPTOM_BACK, null, "BODY_PART", "腰骶", 2, 1),
                new CatalogModels.Symptom(DemoCatalogIds.SYMPTOM_SORE, null, "DISCOMFORT", "酸胀", 3, 1),
                new CatalogModels.Symptom(DemoCatalogIds.SYMPTOM_OTHER, null, "DISCOMFORT", "其他", 9, 1));

        symptomProjects = List.of(
                new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_NECK, DemoCatalogIds.PROJECT_P60),
                new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_NECK, DemoCatalogIds.PROJECT_P45),
                new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_BACK, DemoCatalogIds.PROJECT_P60),
                new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_BACK, DemoCatalogIds.PROJECT_P90),
                new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_SORE, DemoCatalogIds.PROJECT_P60),
                new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_SORE, DemoCatalogIds.PROJECT_P45),
                new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_SORE, DemoCatalogIds.PROJECT_P90));

        List<CatalogModels.ScheduleTemplate> tpls = new ArrayList<>();
        for (long therapistId : List.of(
                DemoCatalogIds.THERAPIST_LIN, DemoCatalogIds.THERAPIST_CHEN, DemoCatalogIds.THERAPIST_ZHOU)) {
            for (int weekday = 1; weekday <= 7; weekday++) {
                tpls.add(new CatalogModels.ScheduleTemplate(
                        therapistId, DemoCatalogIds.STORE, weekday, TEN, TWENTY_TWO, FROM, null, 1));
            }
        }
        templates = List.copyOf(tpls);
    }

    @Override
    public List<CatalogModels.Store> listStores() {
        return stores;
    }

    @Override
    public Optional<CatalogModels.Store> findStore(long id) {
        return stores.stream().filter(s -> s.id() == id).findFirst();
    }

    @Override
    public List<CatalogModels.Therapist> listTherapists() {
        return therapists;
    }

    @Override
    public List<CatalogModels.Project> listProjects() {
        return projects;
    }

    @Override
    public Optional<CatalogModels.Project> findProject(long id) {
        return projects.stream().filter(p -> p.id() == id).findFirst();
    }

    @Override
    public List<CatalogModels.StoreProject> listStoreProjects() {
        return storeProjects;
    }

    @Override
    public List<CatalogModels.Symptom> listSymptoms() {
        return symptoms;
    }

    @Override
    public Optional<CatalogModels.Symptom> findSymptom(long id) {
        return symptoms.stream().filter(s -> s.id() == id).findFirst();
    }

    @Override
    public List<CatalogModels.SymptomProject> listSymptomProjects() {
        return symptomProjects;
    }

    @Override
    public List<CatalogModels.ScheduleTemplate> listTemplates() {
        return templates;
    }
}
