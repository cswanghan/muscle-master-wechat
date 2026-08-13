package com.jisuodashi.admin;

import com.jisuodashi.catalog.CatalogModels;
import com.jisuodashi.catalog.CatalogRepository;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.rbac.StoreScope;
import com.jisuodashi.rbac.StoreScopeContext;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class AdminCatalogService {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final CatalogRepository catalog;
    private final SnowflakeIdGenerator ids;

    public AdminCatalogService(CatalogRepository catalog, SnowflakeIdGenerator ids) {
        this.catalog = catalog;
        this.ids = ids;
    }

    public AdminDtos.Page<AdminDtos.TherapistItem> listTherapists() {
        StoreScope scope = StoreScopeContext.require();
        List<AdminDtos.TherapistItem> items = catalog.listTherapists().stream()
                .filter(t -> scope.contains(t.homeStoreId()))
                .sorted(Comparator.comparingLong(CatalogModels.Therapist::id))
                .map(AdminCatalogService::toTherapist)
                .toList();
        return AdminDtos.Page.of(items);
    }

    public AdminDtos.TherapistItem getTherapist(long id) {
        CatalogModels.Therapist t = catalog.findTherapist(id)
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "技师不存在"));
        StoreScopeContext.require().assertContains(t.homeStoreId());
        return toTherapist(t);
    }

    public AdminDtos.TherapistItem createTherapist(AdminDtos.TherapistUpsertRequest request) {
        CatalogModels.Therapist created = toTherapistModel(ids.nextId(), request, null);
        StoreScopeContext.require().assertContains(created.homeStoreId());
        assertUniqueEmployeeNo(created.employeeNo(), 0L);
        catalog.upsertTherapist(created);
        return toTherapist(created);
    }

    public AdminDtos.TherapistItem updateTherapist(long id, AdminDtos.TherapistUpsertRequest request) {
        CatalogModels.Therapist existing = catalog.findTherapist(id)
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "技师不存在"));
        StoreScopeContext.require().assertContains(existing.homeStoreId());
        CatalogModels.Therapist next = toTherapistModel(id, request, existing);
        StoreScopeContext.require().assertContains(next.homeStoreId());
        assertUniqueEmployeeNo(next.employeeNo(), id);
        catalog.upsertTherapist(next);
        return toTherapist(next);
    }

    public void deleteTherapist(long id) {
        CatalogModels.Therapist existing = catalog.findTherapist(id)
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "技师不存在"));
        StoreScopeContext.require().assertContains(existing.homeStoreId());
        catalog.softDeleteTherapist(id);
    }

    public AdminDtos.Page<AdminDtos.ProjectItem> listProjects() {
        List<AdminDtos.ProjectItem> items = catalog.listProjects().stream()
                .sorted(Comparator.comparingLong(CatalogModels.Project::id))
                .map(AdminCatalogService::toProject)
                .toList();
        return AdminDtos.Page.of(items);
    }

    public AdminDtos.ProjectItem getProject(long id) {
        return toProject(catalog.findProject(id)
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "项目不存在")));
    }

    public AdminDtos.ProjectItem createProject(AdminDtos.ProjectUpsertRequest request) {
        CatalogModels.Project created = toProjectModel(ids.nextId(), request, null);
        assertUniqueProjectCode(created.code(), 0L);
        catalog.upsertProject(created);
        return toProject(created);
    }

    public AdminDtos.ProjectItem updateProject(long id, AdminDtos.ProjectUpsertRequest request) {
        CatalogModels.Project existing = catalog.findProject(id)
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "项目不存在"));
        CatalogModels.Project next = toProjectModel(id, request, existing);
        assertUniqueProjectCode(next.code(), id);
        catalog.upsertProject(next);
        return toProject(next);
    }

    public void deleteProject(long id) {
        catalog.findProject(id).orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "项目不存在"));
        catalog.softDeleteProject(id);
    }

    public AdminDtos.Page<AdminDtos.TemplateItem> listTemplates() {
        StoreScope scope = StoreScopeContext.require();
        List<AdminDtos.TemplateItem> items = catalog.listTemplates().stream()
                .filter(t -> scope.contains(t.storeId()))
                .sorted(Comparator.comparingLong(CatalogModels.ScheduleTemplate::id))
                .map(AdminCatalogService::toTemplate)
                .toList();
        return AdminDtos.Page.of(items);
    }

    public AdminDtos.TemplateItem getTemplate(long id) {
        CatalogModels.ScheduleTemplate t = catalog.findTemplate(id)
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "排班模板不存在"));
        StoreScopeContext.require().assertContains(t.storeId());
        return toTemplate(t);
    }

    public AdminDtos.TemplateItem createTemplate(AdminDtos.TemplateUpsertRequest request) {
        CatalogModels.ScheduleTemplate created = toTemplateModel(ids.nextId(), request, null);
        StoreScopeContext.require().assertContains(created.storeId());
        catalog.upsertTemplate(created);
        return toTemplate(created);
    }

    public AdminDtos.TemplateItem updateTemplate(long id, AdminDtos.TemplateUpsertRequest request) {
        CatalogModels.ScheduleTemplate existing = catalog.findTemplate(id)
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "排班模板不存在"));
        StoreScopeContext.require().assertContains(existing.storeId());
        CatalogModels.ScheduleTemplate next = toTemplateModel(id, request, existing);
        StoreScopeContext.require().assertContains(next.storeId());
        catalog.upsertTemplate(next);
        return toTemplate(next);
    }

    public void deleteTemplate(long id) {
        CatalogModels.ScheduleTemplate existing = catalog.findTemplate(id)
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "排班模板不存在"));
        StoreScopeContext.require().assertContains(existing.storeId());
        catalog.deleteTemplate(id);
    }

    private void assertUniqueEmployeeNo(String employeeNo, long ignoreId) {
        if (catalog.employeeNoTaken(employeeNo, ignoreId)) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "工号已占用");
        }
    }

    private void assertUniqueProjectCode(String code, long ignoreId) {
        if (catalog.projectCodeTaken(code, ignoreId)) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "项目编码已占用");
        }
    }

    private static AdminDtos.TherapistItem toTherapist(CatalogModels.Therapist t) {
        return new AdminDtos.TherapistItem(
                String.valueOf(t.id()),
                t.employeeNo(),
                t.name(),
                String.valueOf(t.homeStoreId()),
                t.level(),
                t.intro(),
                t.ratingX100(),
                t.status(),
                t.projectIds().stream().map(String::valueOf).toList(),
                t.symptomIds().stream().map(String::valueOf).toList());
    }

    private static AdminDtos.ProjectItem toProject(CatalogModels.Project p) {
        return new AdminDtos.ProjectItem(
                String.valueOf(p.id()),
                p.code(),
                p.name(),
                p.durationMinutes(),
                p.bufferMinutes(),
                p.priceFen(),
                p.description(),
                p.status());
    }

    private static AdminDtos.TemplateItem toTemplate(CatalogModels.ScheduleTemplate t) {
        return new AdminDtos.TemplateItem(
                String.valueOf(t.id()),
                String.valueOf(t.therapistId()),
                String.valueOf(t.storeId()),
                t.weekday(),
                t.startTime().format(HM),
                t.endTime().format(HM),
                t.effectiveFrom() == null ? null : t.effectiveFrom().toString(),
                t.effectiveTo() == null ? null : t.effectiveTo().toString(),
                t.status());
    }

    private CatalogModels.Therapist toTherapistModel(
            long id, AdminDtos.TherapistUpsertRequest request, CatalogModels.Therapist existing) {
        if (request == null) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "请求不能为空");
        }
        String employeeNo = firstText(request.employeeNo(), existing == null ? null : existing.employeeNo(), "employeeNo");
        String name = firstText(request.name(), existing == null ? null : existing.name(), "name");
        long homeStoreId = request.homeStoreId() == null || request.homeStoreId().isBlank()
                ? (existing == null ? 0L : existing.homeStoreId())
                : parseId(request.homeStoreId(), "homeStoreId");
        if (homeStoreId == 0L) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "homeStoreId 不能为空");
        }
        String level = firstText(request.level(), existing == null ? "JUNIOR" : existing.level(), "level");
        String intro = request.intro() != null ? request.intro() : (existing == null ? null : existing.intro());
        int status = request.status() == null ? (existing == null ? 1 : existing.status()) : request.status();
        List<Long> projectIds = parseIdList(request.projectIds(), existing == null ? List.of() : existing.projectIds());
        List<Long> symptomIds = parseIdList(request.symptomIds(), existing == null ? List.of() : existing.symptomIds());
        return new CatalogModels.Therapist(
                id,
                existing == null ? 0L : existing.staffUserId(),
                employeeNo,
                name,
                homeStoreId,
                level,
                existing == null ? null : existing.avatarUrl(),
                intro,
                existing == null ? 500 : existing.ratingX100(),
                status,
                projectIds,
                symptomIds);
    }

    private CatalogModels.Project toProjectModel(
            long id, AdminDtos.ProjectUpsertRequest request, CatalogModels.Project existing) {
        if (request == null) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "请求不能为空");
        }
        String code = firstText(request.code(), existing == null ? null : existing.code(), "code");
        String name = firstText(request.name(), existing == null ? null : existing.name(), "name");
        int duration = request.durationMinutes() == null
                ? (existing == null ? 0 : existing.durationMinutes())
                : request.durationMinutes();
        if (duration < 15 || duration % 15 != 0) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "durationMinutes 须为 15 的倍数");
        }
        int buffer = request.bufferMinutes() == null
                ? (existing == null ? 15 : existing.bufferMinutes())
                : request.bufferMinutes();
        if (buffer < 1 || buffer > 15) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "buffer_minutes 须为 1–15");
        }
        long price = request.priceFen() == null
                ? (existing == null ? -1L : existing.priceFen())
                : request.priceFen();
        if (price < 0) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "priceFen 无效");
        }
        String description = request.description() != null
                ? request.description()
                : (existing == null ? null : existing.description());
        int status = request.status() == null ? (existing == null ? 1 : existing.status()) : request.status();
        return new CatalogModels.Project(
                id, code, name, duration, buffer, price, description,
                existing == null ? null : existing.coverUrl(), status);
    }

    private CatalogModels.ScheduleTemplate toTemplateModel(
            long id, AdminDtos.TemplateUpsertRequest request, CatalogModels.ScheduleTemplate existing) {
        if (request == null) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "请求不能为空");
        }
        long therapistId = request.therapistId() == null || request.therapistId().isBlank()
                ? (existing == null ? 0L : existing.therapistId())
                : parseId(request.therapistId(), "therapistId");
        long storeId = request.storeId() == null || request.storeId().isBlank()
                ? (existing == null ? 0L : existing.storeId())
                : parseId(request.storeId(), "storeId");
        if (therapistId == 0L) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "therapistId 不能为空");
        }
        if (storeId == 0L) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "storeId 不能为空");
        }
        int weekday = request.weekday() == null ? (existing == null ? 0 : existing.weekday()) : request.weekday();
        if (weekday < 1 || weekday > 7) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "weekday 须为 1–7");
        }
        LocalTime start = parseTime(request.startTime(), existing == null ? null : existing.startTime(), "startTime");
        LocalTime end = parseTime(request.endTime(), existing == null ? null : existing.endTime(), "endTime");
        if (!start.isBefore(end)) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "startTime 须早于 endTime");
        }
        LocalDate from = parseDate(
                request.effectiveFrom(), existing == null ? null : existing.effectiveFrom(), "effectiveFrom");
        LocalDate to = request.effectiveTo() == null || request.effectiveTo().isBlank()
                ? (existing == null ? null : existing.effectiveTo())
                : parseDate(request.effectiveTo(), null, "effectiveTo");
        int status = request.status() == null ? (existing == null ? 1 : existing.status()) : request.status();
        return new CatalogModels.ScheduleTemplate(
                id, therapistId, storeId, weekday, start, end, from, to, status);
    }

    static long parseId(String raw, String field) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, field + " 无效");
        }
    }

    private static String firstText(String value, String fallback, String field) {
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        throw new ApiException(ErrorCodes.BAD_REQUEST, field + " 不能为空");
    }

    private static List<Long> parseIdList(List<String> raw, List<Long> fallback) {
        if (raw == null) {
            return List.copyOf(fallback);
        }
        List<Long> out = new ArrayList<>();
        for (String item : raw) {
            out.add(parseId(item, "id"));
        }
        return List.copyOf(out);
    }

    private static LocalTime parseTime(String raw, LocalTime fallback, String field) {
        if (raw == null || raw.isBlank()) {
            if (fallback == null) {
                throw new ApiException(ErrorCodes.BAD_REQUEST, field + " 不能为空");
            }
            return fallback;
        }
        try {
            return raw.length() == 5 ? LocalTime.parse(raw, HM) : LocalTime.parse(raw);
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, field + " 无效");
        }
    }

    private static LocalDate parseDate(String raw, LocalDate fallback, String field) {
        if (raw == null || raw.isBlank()) {
            if (fallback == null) {
                throw new ApiException(ErrorCodes.BAD_REQUEST, field + " 不能为空");
            }
            return fallback;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, field + " 无效");
        }
    }
}
