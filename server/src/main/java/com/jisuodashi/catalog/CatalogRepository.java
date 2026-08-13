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

    default Optional<CatalogModels.Room> findRoom(long id) {
        return Optional.empty();
    }

    default Optional<CatalogModels.Bed> findBed(long id) {
        return Optional.empty();
    }

    /** True when inventory has generated any slot for the date. */
    default boolean hasSlotsOn(LocalDate date) {
        return false;
    }

    /** therapist_slot.store_id that day, status <> REST. Empty until inventory generates. */
    default List<Long> therapistIdsOnDutySlots(Long storeId, LocalDate date) {
        return List.of();
    }
}
