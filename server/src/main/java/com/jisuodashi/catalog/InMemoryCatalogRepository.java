package com.jisuodashi.catalog;

import com.jisuodashi.auth.DemoStaffIds;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** H2 cannot apply V1; load the same IDs as V3__demo_store.sql. */
@Repository
@Profile("dev")
public class InMemoryCatalogRepository implements CatalogRepository {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalTime TEN = LocalTime.of(10, 0);
    private static final LocalTime TWENTY_TWO = LocalTime.of(22, 0);

    private final CopyOnWriteArrayList<CatalogModels.Store> stores = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<CatalogModels.Therapist> therapists = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<CatalogModels.Project> projects = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<CatalogModels.StoreProject> storeProjects = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<CatalogModels.Symptom> symptoms = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<CatalogModels.SymptomProject> symptomProjects = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<CatalogModels.ScheduleTemplate> templates = new CopyOnWriteArrayList<>();

    public InMemoryCatalogRepository() {
        seed();
    }

    private void seed() {
        stores.clear();
        therapists.clear();
        projects.clear();
        storeProjects.clear();
        symptoms.clear();
        symptomProjects.clear();
        templates.clear();

        stores.add(new CatalogModels.Store(
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
        therapists.add(new CatalogModels.Therapist(
                DemoCatalogIds.THERAPIST_LIN, DemoStaffIds.T1, "T001", "林晓",
                DemoCatalogIds.STORE, "SENIOR", null, "首席技师，肩颈深层", 490, 1,
                allProjects, List.of(DemoCatalogIds.SYMPTOM_NECK, DemoCatalogIds.SYMPTOM_SORE)));
        therapists.add(new CatalogModels.Therapist(
                DemoCatalogIds.THERAPIST_CHEN, DemoStaffIds.T2, "T002", "陈默",
                DemoCatalogIds.STORE, "MIDDLE", null, "腰背理筋", 480, 1,
                allProjects, List.of(DemoCatalogIds.SYMPTOM_BACK, DemoCatalogIds.SYMPTOM_SORE)));
        therapists.add(new CatalogModels.Therapist(
                DemoCatalogIds.THERAPIST_ZHOU, DemoStaffIds.T3, "T003", "周可",
                DemoCatalogIds.STORE, "JUNIOR", null, "全身放松", 470, 1,
                allProjects, List.of(
                        DemoCatalogIds.SYMPTOM_NECK, DemoCatalogIds.SYMPTOM_BACK, DemoCatalogIds.SYMPTOM_SORE)));

        projects.add(new CatalogModels.Project(
                DemoCatalogIds.PROJECT_P60, "P60", "全身推拿放松", 60, 15, 19800,
                "全身推拿 60 分钟，缓冲 15 分钟", null, 1));
        projects.add(new CatalogModels.Project(
                DemoCatalogIds.PROJECT_P45, "P45", "肩颈专项疏通", 45, 15, 12800,
                "肩颈专项 45 分钟，缓冲 15 分钟", null, 1));
        projects.add(new CatalogModels.Project(
                DemoCatalogIds.PROJECT_P90, "P90", "腰背深层理筋", 90, 15, 26800,
                "腰背理筋 90 分钟，缓冲 15 分钟", null, 1));

        storeProjects.add(new CatalogModels.StoreProject(DemoCatalogIds.STORE, DemoCatalogIds.PROJECT_P60, null, 1));
        storeProjects.add(new CatalogModels.StoreProject(DemoCatalogIds.STORE, DemoCatalogIds.PROJECT_P45, null, 1));
        storeProjects.add(new CatalogModels.StoreProject(DemoCatalogIds.STORE, DemoCatalogIds.PROJECT_P90, null, 1));

        symptoms.add(new CatalogModels.Symptom(DemoCatalogIds.SYMPTOM_NECK, null, "BODY_PART", "肩颈", 1, 1));
        symptoms.add(new CatalogModels.Symptom(DemoCatalogIds.SYMPTOM_BACK, null, "BODY_PART", "腰骶", 2, 1));
        symptoms.add(new CatalogModels.Symptom(DemoCatalogIds.SYMPTOM_SORE, null, "DISCOMFORT", "酸胀", 3, 1));
        symptoms.add(new CatalogModels.Symptom(DemoCatalogIds.SYMPTOM_OTHER, null, "DISCOMFORT", "其他", 9, 1));

        symptomProjects.add(new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_NECK, DemoCatalogIds.PROJECT_P60));
        symptomProjects.add(new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_NECK, DemoCatalogIds.PROJECT_P45));
        symptomProjects.add(new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_BACK, DemoCatalogIds.PROJECT_P60));
        symptomProjects.add(new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_BACK, DemoCatalogIds.PROJECT_P90));
        symptomProjects.add(new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_SORE, DemoCatalogIds.PROJECT_P60));
        symptomProjects.add(new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_SORE, DemoCatalogIds.PROJECT_P45));
        symptomProjects.add(new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_SORE, DemoCatalogIds.PROJECT_P90));

        for (long therapistId : List.of(
                DemoCatalogIds.THERAPIST_LIN, DemoCatalogIds.THERAPIST_CHEN, DemoCatalogIds.THERAPIST_ZHOU)) {
            for (int weekday = 1; weekday <= 7; weekday++) {
                templates.add(new CatalogModels.ScheduleTemplate(
                        DemoCatalogIds.templateId(therapistId, weekday),
                        therapistId, DemoCatalogIds.STORE, weekday, TEN, TWENTY_TWO, FROM, null, 1));
            }
        }
    }

    @Override
    public synchronized void resetDemo() {
        seed();
    }

    @Override
    public List<CatalogModels.Store> listStores() {
        return List.copyOf(stores);
    }

    @Override
    public Optional<CatalogModels.Store> findStore(long id) {
        return stores.stream().filter(s -> s.id() == id).findFirst();
    }

    @Override
    public List<CatalogModels.Therapist> listTherapists() {
        return List.copyOf(therapists);
    }

    @Override
    public Optional<CatalogModels.Therapist> findTherapist(long id) {
        return therapists.stream().filter(t -> t.id() == id).findFirst();
    }

    @Override
    public List<CatalogModels.Project> listProjects() {
        return List.copyOf(projects);
    }

    @Override
    public Optional<CatalogModels.Project> findProject(long id) {
        return projects.stream().filter(p -> p.id() == id).findFirst();
    }

    @Override
    public List<CatalogModels.StoreProject> listStoreProjects() {
        return List.copyOf(storeProjects);
    }

    @Override
    public List<CatalogModels.Symptom> listSymptoms() {
        return List.copyOf(symptoms);
    }

    @Override
    public Optional<CatalogModels.Symptom> findSymptom(long id) {
        return symptoms.stream().filter(s -> s.id() == id).findFirst();
    }

    @Override
    public List<CatalogModels.SymptomProject> listSymptomProjects() {
        return List.copyOf(symptomProjects);
    }

    @Override
    public List<CatalogModels.ScheduleTemplate> listTemplates() {
        return List.copyOf(templates);
    }

    @Override
    public Optional<CatalogModels.ScheduleTemplate> findTemplate(long id) {
        return templates.stream().filter(t -> t.id() == id).findFirst();
    }

    @Override
    public synchronized void upsertTherapist(CatalogModels.Therapist therapist) {
        therapists.removeIf(t -> t.id() == therapist.id());
        therapists.add(therapist);
    }

    @Override
    public synchronized void softDeleteTherapist(long id) {
        therapists.replaceAll(t -> t.id() == id
                ? new CatalogModels.Therapist(
                t.id(), t.staffUserId(), t.employeeNo(), t.name(), t.homeStoreId(), t.level(),
                t.avatarUrl(), t.intro(), t.ratingX100(), 0, t.projectIds(), t.symptomIds())
                : t);
    }

    @Override
    public synchronized void upsertProject(CatalogModels.Project project) {
        projects.removeIf(p -> p.id() == project.id());
        projects.add(project);
    }

    @Override
    public synchronized void softDeleteProject(long id) {
        projects.replaceAll(p -> p.id() == id
                ? new CatalogModels.Project(
                p.id(), p.code(), p.name(), p.durationMinutes(), p.bufferMinutes(),
                p.priceFen(), p.description(), p.coverUrl(), 0)
                : p);
    }

    @Override
    public synchronized void upsertTemplate(CatalogModels.ScheduleTemplate template) {
        templates.removeIf(t -> t.id() == template.id());
        templates.add(template);
    }

    @Override
    public synchronized void deleteTemplate(long id) {
        templates.removeIf(t -> t.id() == id);
    }
}
