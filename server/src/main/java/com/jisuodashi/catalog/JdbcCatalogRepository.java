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
    public Optional<CatalogModels.Therapist> findTherapist(long id) {
        return listTherapists().stream().filter(t -> t.id() == id).findFirst();
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
                SELECT id, therapist_id, store_id, weekday, start_time, end_time,
                       effective_from, effective_to, status
                FROM schedule_template
                """,
                (rs, i) -> new CatalogModels.ScheduleTemplate(
                        rs.getLong("id"),
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
    public Optional<CatalogModels.ScheduleTemplate> findTemplate(long id) {
        return listTemplates().stream().filter(t -> t.id() == id).findFirst();
    }

    @Override
    public void upsertTherapist(CatalogModels.Therapist therapist) {
        Integer exists = jdbc.query(
                "SELECT 1 FROM therapist WHERE id=?",
                rs -> rs.next() ? 1 : null,
                therapist.id());
        if (exists == null) {
            jdbc.update(
                    """
                    INSERT INTO therapist (
                      id, staff_user_id, employee_no, name, home_store_id, level,
                      avatar_url, intro, rating_x100, status, created_at, updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))
                    """,
                    therapist.id(),
                    therapist.staffUserId() == 0 ? null : therapist.staffUserId(),
                    therapist.employeeNo(),
                    therapist.name(),
                    therapist.homeStoreId(),
                    therapist.level(),
                    therapist.avatarUrl(),
                    therapist.intro(),
                    therapist.ratingX100(),
                    therapist.status());
        } else {
            jdbc.update(
                    """
                    UPDATE therapist SET staff_user_id=?, employee_no=?, name=?, home_store_id=?,
                      level=?, avatar_url=?, intro=?, rating_x100=?, status=?,
                      updated_at=CURRENT_TIMESTAMP(3), deleted_at=NULL
                    WHERE id=?
                    """,
                    therapist.staffUserId() == 0 ? null : therapist.staffUserId(),
                    therapist.employeeNo(),
                    therapist.name(),
                    therapist.homeStoreId(),
                    therapist.level(),
                    therapist.avatarUrl(),
                    therapist.intro(),
                    therapist.ratingX100(),
                    therapist.status(),
                    therapist.id());
        }
        jdbc.update("DELETE FROM therapist_project WHERE therapist_id=?", therapist.id());
        jdbc.update("DELETE FROM therapist_symptom WHERE therapist_id=?", therapist.id());
        for (Long projectId : therapist.projectIds()) {
            jdbc.update(
                    "INSERT INTO therapist_project (therapist_id, project_id) VALUES (?,?)",
                    therapist.id(), projectId);
        }
        for (Long symptomId : therapist.symptomIds()) {
            jdbc.update(
                    "INSERT INTO therapist_symptom (therapist_id, symptom_id) VALUES (?,?)",
                    therapist.id(), symptomId);
        }
    }

    @Override
    public void softDeleteTherapist(long id) {
        jdbc.update(
                "UPDATE therapist SET status=0, deleted_at=CURRENT_TIMESTAMP(3), updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",
                id);
    }

    @Override
    public void upsertProject(CatalogModels.Project project) {
        Integer exists = jdbc.query(
                "SELECT 1 FROM project WHERE id=?",
                rs -> rs.next() ? 1 : null,
                project.id());
        if (exists == null) {
            jdbc.update(
                    """
                    INSERT INTO project (
                      id, code, name, duration_minutes, buffer_minutes, price_fen,
                      description, cover_url, status, created_at, updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))
                    """,
                    project.id(),
                    project.code(),
                    project.name(),
                    project.durationMinutes(),
                    project.bufferMinutes(),
                    project.priceFen(),
                    project.description(),
                    project.coverUrl(),
                    project.status());
        } else {
            jdbc.update(
                    """
                    UPDATE project SET code=?, name=?, duration_minutes=?, buffer_minutes=?,
                      price_fen=?, description=?, cover_url=?, status=?,
                      updated_at=CURRENT_TIMESTAMP(3), deleted_at=NULL
                    WHERE id=?
                    """,
                    project.code(),
                    project.name(),
                    project.durationMinutes(),
                    project.bufferMinutes(),
                    project.priceFen(),
                    project.description(),
                    project.coverUrl(),
                    project.status(),
                    project.id());
        }
    }

    @Override
    public void softDeleteProject(long id) {
        jdbc.update(
                "UPDATE project SET status=0, deleted_at=CURRENT_TIMESTAMP(3), updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",
                id);
    }

    @Override
    public void upsertTemplate(CatalogModels.ScheduleTemplate template) {
        Integer exists = jdbc.query(
                "SELECT 1 FROM schedule_template WHERE id=?",
                rs -> rs.next() ? 1 : null,
                template.id());
        if (exists == null) {
            jdbc.update(
                    """
                    INSERT INTO schedule_template (
                      id, therapist_id, store_id, weekday, start_time, end_time,
                      effective_from, effective_to, status, created_at, updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))
                    """,
                    template.id(),
                    template.therapistId(),
                    template.storeId(),
                    template.weekday(),
                    template.startTime(),
                    template.endTime(),
                    Date.valueOf(template.effectiveFrom()),
                    template.effectiveTo() == null ? null : Date.valueOf(template.effectiveTo()),
                    template.status());
        } else {
            jdbc.update(
                    """
                    UPDATE schedule_template SET therapist_id=?, store_id=?, weekday=?,
                      start_time=?, end_time=?, effective_from=?, effective_to=?, status=?,
                      updated_at=CURRENT_TIMESTAMP(3)
                    WHERE id=?
                    """,
                    template.therapistId(),
                    template.storeId(),
                    template.weekday(),
                    template.startTime(),
                    template.endTime(),
                    Date.valueOf(template.effectiveFrom()),
                    template.effectiveTo() == null ? null : Date.valueOf(template.effectiveTo()),
                    template.status(),
                    template.id());
        }
    }

    @Override
    public void deleteTemplate(long id) {
        jdbc.update("DELETE FROM schedule_template WHERE id=?", id);
    }

    @Override
    public boolean employeeNoTaken(String employeeNo, long ignoreId) {
        Integer one = jdbc.query(
                "SELECT 1 FROM therapist WHERE employee_no=? AND id<>? LIMIT 1",
                rs -> rs.next() ? 1 : null,
                employeeNo,
                ignoreId);
        return one != null;
    }

    @Override
    public boolean projectCodeTaken(String code, long ignoreId) {
        Integer one = jdbc.query(
                "SELECT 1 FROM project WHERE code=? AND id<>? LIMIT 1",
                rs -> rs.next() ? 1 : null,
                code,
                ignoreId);
        return one != null;
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
