package com.jisuodashi.catalog;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@Profile("!dev")
public class JdbcCatalogRepository implements CatalogRepository {

    private static final RowMapper<CatalogModels.Store> STORE = (rs, i) -> new CatalogModels.Store(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getBytes("phone_cipher"),
            rs.getBytes("address_cipher"),
            rs.getBigDecimal("lng"),
            rs.getBigDecimal("lat"),
            rs.getObject("business_start", LocalTime.class),
            rs.getObject("business_end", LocalTime.class),
            rs.getString("timezone"),
            rs.getInt("status"));

    private static final RowMapper<CatalogModels.Project> PROJECT = (rs, i) -> new CatalogModels.Project(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getInt("duration_minutes"),
            rs.getInt("buffer_minutes"),
            rs.getLong("price_fen"),
            rs.getString("description"),
            rs.getString("cover_url"),
            rs.getInt("status"));

    private static final RowMapper<CatalogModels.Symptom> SYMPTOM = (rs, i) -> new CatalogModels.Symptom(
            rs.getLong("id"),
            (Long) rs.getObject("parent_id"),
            rs.getString("type"),
            rs.getString("name"),
            rs.getInt("sort_no"),
            rs.getInt("status"));

    private final JdbcTemplate jdbc;

    public JdbcCatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<CatalogModels.Store> listStores() {
        return jdbc.query("SELECT * FROM store WHERE deleted_at IS NULL", STORE);
    }

    @Override
    public Optional<CatalogModels.Store> findStore(long id) {
        List<CatalogModels.Store> rows =
                jdbc.query("SELECT * FROM store WHERE id=? AND deleted_at IS NULL", STORE, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public List<CatalogModels.Therapist> listTherapists() {
        Map<Long, List<Long>> projects = new LinkedHashMap<>();
        jdbc.query("SELECT therapist_id, project_id FROM therapist_project", rs -> {
            projects.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getLong(2));
        });
        Map<Long, List<Long>> symptoms = new LinkedHashMap<>();
        jdbc.query("SELECT therapist_id, symptom_id FROM therapist_symptom", rs -> {
            symptoms.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getLong(2));
        });
        return jdbc.query(
                "SELECT * FROM therapist WHERE deleted_at IS NULL",
                (rs, i) -> {
                    long id = rs.getLong("id");
                    long staffId = rs.getLong("staff_user_id");
                    if (rs.wasNull()) {
                        staffId = 0L;
                    }
                    return new CatalogModels.Therapist(
                            id,
                            staffId,
                            rs.getString("employee_no"),
                            rs.getString("name"),
                            rs.getLong("home_store_id"),
                            rs.getString("level"),
                            rs.getString("avatar_url"),
                            rs.getString("intro"),
                            rs.getInt("rating_x100"),
                            rs.getInt("status"),
                            List.copyOf(projects.getOrDefault(id, List.of())),
                            List.copyOf(symptoms.getOrDefault(id, List.of())));
                });
    }

    @Override
    public List<CatalogModels.Project> listProjects() {
        return jdbc.query("SELECT * FROM project WHERE deleted_at IS NULL", PROJECT);
    }

    @Override
    public Optional<CatalogModels.Project> findProject(long id) {
        List<CatalogModels.Project> rows =
                jdbc.query("SELECT * FROM project WHERE id=? AND deleted_at IS NULL", PROJECT, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public List<CatalogModels.StoreProject> listStoreProjects() {
        return jdbc.query(
                "SELECT store_id, project_id, price_fen, status FROM store_project",
                (rs, i) -> {
                    long price = rs.getLong("price_fen");
                    Long priceFen = rs.wasNull() ? null : price;
                    return new CatalogModels.StoreProject(
                            rs.getLong("store_id"),
                            rs.getLong("project_id"),
                            priceFen,
                            rs.getInt("status"));
                });
    }

    @Override
    public List<CatalogModels.Symptom> listSymptoms() {
        return jdbc.query("SELECT * FROM symptom", SYMPTOM);
    }

    @Override
    public Optional<CatalogModels.Symptom> findSymptom(long id) {
        List<CatalogModels.Symptom> rows = jdbc.query("SELECT * FROM symptom WHERE id=?", SYMPTOM, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public List<CatalogModels.SymptomProject> listSymptomProjects() {
        return jdbc.query(
                "SELECT symptom_id, project_id FROM symptom_project",
                (rs, i) -> new CatalogModels.SymptomProject(rs.getLong(1), rs.getLong(2)));
    }

    @Override
    public List<CatalogModels.ScheduleTemplate> listTemplates() {
        return jdbc.query(
                """
                SELECT therapist_id, store_id, weekday, start_time, end_time,
                       effective_from, effective_to, status
                FROM schedule_template
                """,
                (rs, i) -> new CatalogModels.ScheduleTemplate(
                        rs.getLong("therapist_id"),
                        rs.getLong("store_id"),
                        rs.getInt("weekday"),
                        rs.getObject("start_time", LocalTime.class),
                        rs.getObject("end_time", LocalTime.class),
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getObject("effective_to", LocalDate.class),
                        rs.getInt("status")));
    }

    @Override
    public boolean hasSlotsOn(LocalDate date) {
        Integer one = jdbc.query(
                "SELECT 1 FROM therapist_slot WHERE slot_date=? LIMIT 1",
                rs -> rs.next() ? 1 : null,
                Date.valueOf(date));
        return one != null;
    }

    @Override
    public List<Long> therapistIdsOnDutySlots(Long storeId, LocalDate date) {
        if (storeId == null) {
            return jdbc.query(
                    "SELECT DISTINCT therapist_id FROM therapist_slot WHERE slot_date=? AND status <> 'REST'",
                    (rs, i) -> rs.getLong(1),
                    Date.valueOf(date));
        }
        return jdbc.query(
                """
                SELECT DISTINCT therapist_id FROM therapist_slot
                WHERE slot_date=? AND store_id=? AND status <> 'REST'
                """,
                (rs, i) -> rs.getLong(1),
                Date.valueOf(date),
                storeId);
    }
}
