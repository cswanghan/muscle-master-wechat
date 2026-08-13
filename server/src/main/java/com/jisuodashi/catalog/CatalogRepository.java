package com.jisuodashi.catalog;

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
}
