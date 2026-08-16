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
    private final CopyOnWriteArrayList<CatalogModels.Therapist> deletedTherapists = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<CatalogModels.Project> projects = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<CatalogModels.Project> deletedProjects = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<CatalogModels.StoreProject> storeProjects = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<CatalogModels.Symptom> symptoms = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<CatalogModels.SymptomProject> symptomProjects = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<CatalogModels.ScheduleTemplate> templates = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<CatalogModels.Room> rooms = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<CatalogModels.Bed> beds = new CopyOnWriteArrayList<>();

    public InMemoryCatalogRepository() {
        seed();
    }

    private void seed() {
        stores.clear();
        therapists.clear();
        deletedTherapists.clear();
        projects.clear();
        deletedProjects.clear();
        storeProjects.clear();
        symptoms.clear();
        symptomProjects.clear();
        templates.clear();
        rooms.clear();
        beds.clear();

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
        stores.add(new CatalogModels.Store(
                DemoCatalogIds.STORE_EAST,
                "DEMO02",
                "肌松大师·演示二分店",
                null,
                null,
                new BigDecimal("121.5000000"),
                new BigDecimal("31.2400000"),
                TEN,
                TWENTY_TWO,
                "Asia/Shanghai",
                1));

        stores.add(new CatalogModels.Store(
                DemoCatalogIds.STORE_DARK,
                "DEMO03",
                "肌松大师·未开放门店",
                null,
                null,
                new BigDecimal("121.5000000"),
                new BigDecimal("31.2400000"),
                TEN,
                TWENTY_TWO,
                "Asia/Shanghai",
                1));

        List<Long> allProjects = List.of(
                DemoCatalogIds.PROJECT_P60, DemoCatalogIds.PROJECT_P45, DemoCatalogIds.PROJECT_P90);
        List<Long> allSymptoms = List.of(
                DemoCatalogIds.SYMPTOM_NECK, DemoCatalogIds.SYMPTOM_BACK, DemoCatalogIds.SYMPTOM_SORE);
        for (DemoFixtures.TherapistSeed s : DemoFixtures.therapists()) {
            therapists.add(new CatalogModels.Therapist(
                    s.therapistId(), s.staffUserId(), s.employeeNo(), s.name(),
                    s.storeId(), s.level(), null, intro(s.level()), s.ratingX100(), 1,
                    allProjects, allSymptoms));
        }

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
        symptoms.add(new CatalogModels.Symptom(DemoCatalogIds.SYMPTOM_ARM, null, "BODY_PART", "手臂", 3, 1));
        symptoms.add(new CatalogModels.Symptom(DemoCatalogIds.SYMPTOM_LEG, null, "BODY_PART", "腿足", 4, 1));
        symptoms.add(new CatalogModels.Symptom(DemoCatalogIds.SYMPTOM_SIT, null, "DISCOMFORT", "久坐僵硬", 5, 1));
        symptoms.add(new CatalogModels.Symptom(DemoCatalogIds.SYMPTOM_SLEEP, null, "DISCOMFORT", "睡不好", 6, 1));
        symptoms.add(new CatalogModels.Symptom(DemoCatalogIds.SYMPTOM_BACK_PAIN, null, "DISCOMFORT", "腰酸背痛", 7, 1));
        symptoms.add(new CatalogModels.Symptom(DemoCatalogIds.SYMPTOM_STIFF, null, "DISCOMFORT", "肩周僵硬", 8, 1));
        symptoms.add(new CatalogModels.Symptom(DemoCatalogIds.SYMPTOM_HEAD, null, "DISCOMFORT", "头痛头晕", 9, 1));
        symptoms.add(new CatalogModels.Symptom(DemoCatalogIds.SYMPTOM_POSTPARTUM, null, "DISCOMFORT", "产后调理", 10, 1));
        symptoms.add(new CatalogModels.Symptom(DemoCatalogIds.SYMPTOM_FOOT, null, "DISCOMFORT", "足底疲劳", 11, 1));
        symptoms.add(new CatalogModels.Symptom(DemoCatalogIds.SYMPTOM_SORE, null, "DISCOMFORT", "酸胀", 12, 1));
        symptoms.add(new CatalogModels.Symptom(DemoCatalogIds.SYMPTOM_OTHER, null, "DISCOMFORT", "其他", 19, 1));

        symptomProjects.add(new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_NECK, DemoCatalogIds.PROJECT_P60));
        symptomProjects.add(new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_NECK, DemoCatalogIds.PROJECT_P45));
        symptomProjects.add(new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_BACK, DemoCatalogIds.PROJECT_P60));
        symptomProjects.add(new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_BACK, DemoCatalogIds.PROJECT_P90));
        symptomProjects.add(new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_ARM, DemoCatalogIds.PROJECT_P45));
        symptomProjects.add(new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_LEG, DemoCatalogIds.PROJECT_P60));
        symptomProjects.add(new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_SIT, DemoCatalogIds.PROJECT_P45));
        symptomProjects.add(new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_SIT, DemoCatalogIds.PROJECT_P60));
        symptomProjects.add(new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_SLEEP, DemoCatalogIds.PROJECT_P60));
        symptomProjects.add(new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_BACK_PAIN, DemoCatalogIds.PROJECT_P90));
        symptomProjects.add(new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_STIFF, DemoCatalogIds.PROJECT_P45));
        symptomProjects.add(new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_HEAD, DemoCatalogIds.PROJECT_P45));
        symptomProjects.add(new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_POSTPARTUM, DemoCatalogIds.PROJECT_P60));
        symptomProjects.add(new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_FOOT, DemoCatalogIds.PROJECT_P60));
        symptomProjects.add(new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_SORE, DemoCatalogIds.PROJECT_P60));
        symptomProjects.add(new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_SORE, DemoCatalogIds.PROJECT_P45));
        symptomProjects.add(new CatalogModels.SymptomProject(DemoCatalogIds.SYMPTOM_SORE, DemoCatalogIds.PROJECT_P90));

        for (DemoFixtures.TherapistSeed s : DemoFixtures.therapists()) {
            for (int weekday = 1; weekday <= 7; weekday++) {
                templates.add(new CatalogModels.ScheduleTemplate(
                        DemoCatalogIds.templateId(s.therapistId(), weekday),
                        s.therapistId(), s.storeId(), weekday, TEN, TWENTY_TWO, FROM, null, 1));
            }
        }

        rooms.add(new CatalogModels.Room(
                DemoFixtures.ROOM_MAIN, DemoCatalogIds.STORE, "一号房", 1, 1));
        rooms.add(new CatalogModels.Room(
                DemoFixtures.ROOM_EAST, DemoCatalogIds.STORE_EAST, "一号房", 1, 1));
        for (DemoFixtures.BedSeed b : DemoFixtures.beds()) {
            beds.add(new CatalogModels.Bed(b.bedId(), b.storeId(), b.roomId(), b.name(), b.sortNo(), 1));
        }
    }

    private static String intro(String level) {
        return switch (level) {
            case "SENIOR" -> "资深技师，深层手法";
            case "MIDDLE" -> "中级技师，肩颈腰背";
            default -> "全身放松";
        };
    }

    @Override
    public synchronized void resetDemo() {
        seed();
    }

    public void putStore(CatalogModels.Store store) {
        stores.removeIf(s -> s.id() == store.id());
        stores.add(store);
    }

    public void putTherapist(CatalogModels.Therapist therapist) {
        therapists.removeIf(t -> t.id() == therapist.id());
        therapists.add(therapist);
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
    public Optional<CatalogModels.Room> findRoom(long id) {
        return rooms.stream().filter(r -> r.id() == id).findFirst();
    }

    @Override
    public Optional<CatalogModels.Bed> findBed(long id) {
        return beds.stream().filter(b -> b.id() == id).findFirst();
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
        deletedTherapists.removeIf(t -> t.id() == therapist.id());
        therapists.add(therapist);
    }

    @Override
    public synchronized void softDeleteTherapist(long id) {
        therapists.stream().filter(t -> t.id() == id).findFirst().ifPresent(t -> {
            therapists.remove(t);
            deletedTherapists.add(new CatalogModels.Therapist(
                    t.id(), t.staffUserId(), t.employeeNo(), t.name(), t.homeStoreId(), t.level(),
                    t.avatarUrl(), t.intro(), t.ratingX100(), 0, t.projectIds(), t.symptomIds()));
        });
    }

    @Override
    public synchronized void upsertProject(CatalogModels.Project project) {
        projects.removeIf(p -> p.id() == project.id());
        deletedProjects.removeIf(p -> p.id() == project.id());
        projects.add(project);
    }

    @Override
    public synchronized void softDeleteProject(long id) {
        projects.stream().filter(p -> p.id() == id).findFirst().ifPresent(p -> {
            projects.remove(p);
            deletedProjects.add(new CatalogModels.Project(
                    p.id(), p.code(), p.name(), p.durationMinutes(), p.bufferMinutes(),
                    p.priceFen(), p.description(), p.coverUrl(), 0));
        });
    }

    @Override
    public boolean employeeNoTaken(String employeeNo, long ignoreId) {
        return therapists.stream().anyMatch(t -> t.id() != ignoreId && employeeNo.equals(t.employeeNo()))
                || deletedTherapists.stream().anyMatch(t -> t.id() != ignoreId && employeeNo.equals(t.employeeNo()));
    }

    @Override
    public boolean projectCodeTaken(String code, long ignoreId) {
        return projects.stream().anyMatch(p -> p.id() != ignoreId && code.equals(p.code()))
                || deletedProjects.stream().anyMatch(p -> p.id() != ignoreId && code.equals(p.code()));
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
