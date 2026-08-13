package com.jisuodashi.catalog;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CatalogRepository {

    List<CatalogModels.Store> listStores();

    Optional<CatalogModels.Store> findStore(long id);

    List<CatalogModels.Therapist> listTherapists();

    List<CatalogModels.Project> listProjects();

    Optional<CatalogModels.Project> findProject(long id);

    List<CatalogModels.StoreProject> listStoreProjects();

    List<CatalogModels.Symptom> listSymptoms();

    Optional<CatalogModels.Symptom> findSymptom(long id);

    List<CatalogModels.SymptomProject> listSymptomProjects();

    List<CatalogModels.ScheduleTemplate> listTemplates();

    Optional<CatalogModels.Therapist> findTherapist(long id);

    Optional<CatalogModels.ScheduleTemplate> findTemplate(long id);

    void upsertTherapist(CatalogModels.Therapist therapist);

    void softDeleteTherapist(long id);

    void upsertProject(CatalogModels.Project project);

    void softDeleteProject(long id);

    void upsertTemplate(CatalogModels.ScheduleTemplate template);

    void deleteTemplate(long id);

    /** Live + soft-deleted. employee_no / code are never reused. */
    boolean employeeNoTaken(String employeeNo, long ignoreId);

    boolean projectCodeTaken(String code, long ignoreId);

    default void resetDemo() {
    }

    /** True when inventory has generated any slot for the date. */
    default boolean hasSlotsOn(LocalDate date) {
        return false;
    }

    /** therapist_slot.store_id that day, status <> REST. Empty until inventory generates. */
    default List<Long> therapistIdsOnDutySlots(Long storeId, LocalDate date) {
        return List.of();
    }

    default Optional<CatalogModels.Room> findRoom(long id) {
        return Optional.empty();
    }

    default Optional<CatalogModels.Bed> findBed(long id) {
        return Optional.empty();
    }
}
