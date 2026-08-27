package com.jisuodashi.employment;

import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.auth.StaffUser;
import com.jisuodashi.auth.StaffUserRepository;
import com.jisuodashi.catalog.CatalogModels;
import com.jisuodashi.catalog.CatalogRepository;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import com.jisuodashi.inventory.SlotTimes;
import com.jisuodashi.rbac.PermissionCatalog;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 入离职。{@code staff_user.status} 是当前态，{@code employment_log} 是过程。
 *
 * <p>离职走软停用而不是删除：历史订单、业绩、评价都指向这个人，删了那些记录就断了线索。
 */
@Service
public class EmploymentService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    /** 离职后仍会发生的服务：这些单必须先处理掉。 */
    private static final List<String> LIVE = List.of("PENDING_PAY", "BOOKED", "CHECKED_IN", "IN_SERVICE");
    private static final int LOOKAHEAD_DAYS = 60;
    /**
     * 入职能发的角色。故意不含 SUPER_ADMIN / FINANCE —— 拿着 staff:manage 的店长
     * 否则可以给自己开一个超管账号，一步绕开整套权限。这两个角色走 DBA。
     */
    private static final List<String> GRANTABLE =
            List.of("THERAPIST", "FRONTDESK", "STORE_MANAGER", "OPS");

    private final StaffUserRepository staff;
    private final EmploymentStore logs;
    private final CatalogRepository catalog;
    private final SlotOccupyService occupy;
    private final SnowflakeIdGenerator ids;
    private final AppClock clock;

    public EmploymentService(
            StaffUserRepository staff,
            EmploymentStore logs,
            CatalogRepository catalog,
            SlotOccupyService occupy,
            SnowflakeIdGenerator ids,
            AppClock clock) {
        this.staff = staff;
        this.logs = logs;
        this.catalog = catalog;
        this.occupy = occupy;
        this.ids = ids;
        this.clock = clock;
    }

    public EmploymentDtos.StaffListResponse list(String statusRaw) {
        Integer want = statusRaw == null || statusRaw.isBlank() ? null : Integer.valueOf(statusRaw.trim());
        List<EmploymentDtos.StaffItem> items = new ArrayList<>();
        for (StaffUser s : staff.listAll()) {
            if (want != null && s.getStatus() != want) {
                continue;
            }
            Optional<CatalogModels.Therapist> t = therapistOf(s.getId());
            items.add(new EmploymentDtos.StaffItem(
                    String.valueOf(s.getId()),
                    s.getUsername(),
                    s.getName(),
                    s.getStatus(),
                    s.getStatus() == 1 ? "在职" : "已离职",
                    s.getRoleCodes(),
                    s.getScopeType(),
                    t.map(x -> String.valueOf(x.id())).orElse(null),
                    t.map(CatalogModels.Therapist::level).orElse(null),
                    t.map(x -> liveOrders(x.id()).size()).orElse(0)));
        }
        return new EmploymentDtos.StaffListResponse(items, items.size());
    }

    public EmploymentDtos.ActionResponse onboard(EmploymentDtos.OnboardRequest req) {
        long operator = AuthContext.requireStaff().staffId();
        if (staff.findByUsername(req.username()).isPresent()) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "用户名已存在");
        }
        List<String> roles = req.roleCodes() == null || req.roleCodes().isEmpty()
                ? List.of("THERAPIST") : req.roleCodes();
        for (String r : roles) {
            if (!GRANTABLE.contains(r)) {
                throw new ApiException(ErrorCodes.FORBIDDEN, "不可分配的角色：" + r);
            }
        }
        List<Long> storeIds = parseStoreIds(req.storeIds());
        Instant now = Instant.now(clock.clock());
        StaffUser s = new StaffUser();
        s.setId(ids.nextId());
        s.setUsername(req.username().trim());
        s.setName(req.name().trim());
        s.setStatus(1);
        s.setRoleCodes(roles);
        s.setScopeType(req.scopeType() == null || req.scopeType().isBlank()
                ? (roles.contains("THERAPIST") ? "SELF" : "STORE")
                : req.scopeType());
        s.setStoreIds(storeIds);
        s.setPermissionCodes(new ArrayList<>(PermissionCatalog.forRoles(roles)));
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        staff.insert(s);

        // 技师光有账号还上不了钟：排班、可选技师、业绩都挂在 catalog 的 therapist 行上。
        String therapistId = roles.contains("THERAPIST") ? createTherapist(s, storeIds) : null;

        LocalDate on = parseDate(req.effectiveOn(), clock.today());
        logs.insert(new EmploymentModels.Log(
                ids.nextId(), s.getId(), EmploymentModels.ONBOARD, on, null, operator, now));
        return new EmploymentDtos.ActionResponse(
                String.valueOf(s.getId()), therapistId,
                EmploymentModels.ONBOARD, 1, on.toString(), List.of());
    }

    /**
     * 离职。名下还有未来的活单时默认拒绝，并把冲突单一并返回 —— 直接停用会让那些顾客
     * 到店找不到人，而系统这边看起来一切正常。确认改约/退款后带 force 再来。
     */
    public EmploymentDtos.ActionResponse offboard(String staffIdRaw, EmploymentDtos.OffboardRequest req) {
        long operator = AuthContext.requireStaff().staffId();
        StaffUser s = requireStaff(staffIdRaw);
        if (s.getStatus() != 1) {
            throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "该员工已离职");
        }
        List<EmploymentDtos.BlockedOrder> blocked = therapistOf(s.getId())
                .map(t -> liveOrders(t.id()))
                .orElse(List.of());
        boolean force = req != null && Boolean.TRUE.equals(req.force());
        if (!blocked.isEmpty() && !force) {
            return new EmploymentDtos.ActionResponse(
                    String.valueOf(s.getId()), null,
                    EmploymentModels.OFFBOARD, s.getStatus(), null, blocked);
        }
        Instant now = Instant.now(clock.clock());
        s.setStatus(0);
        s.setUpdatedAt(now);
        staff.update(s);
        // 技师同步下架：排班和可选技师列表都按 status 过滤，历史订单仍查得到这个人。
        therapistOf(s.getId()).ifPresent(t -> catalog.upsertTherapist(new CatalogModels.Therapist(
                t.id(), t.staffUserId(), t.employeeNo(), t.name(), t.homeStoreId(),
                t.level(), t.avatarUrl(), t.intro(), t.ratingX100(), 0,
                t.projectIds(), t.symptomIds())));

        LocalDate on = parseDate(req == null ? null : req.effectiveOn(), clock.today());
        logs.insert(new EmploymentModels.Log(
                ids.nextId(), s.getId(), EmploymentModels.OFFBOARD, on,
                req == null ? null : req.reason(), operator, now));
        return new EmploymentDtos.ActionResponse(
                String.valueOf(s.getId()), null,
                EmploymentModels.OFFBOARD, 0, on.toString(), blocked);
    }

    /** 复职：账号与历史都还在，翻回 status=1 即可，不新建人。 */
    public EmploymentDtos.ActionResponse rehire(String staffIdRaw, EmploymentDtos.OffboardRequest req) {
        long operator = AuthContext.requireStaff().staffId();
        StaffUser s = requireStaff(staffIdRaw);
        if (s.getStatus() == 1) {
            throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "该员工在职中");
        }
        Instant now = Instant.now(clock.clock());
        s.setStatus(1);
        s.setUpdatedAt(now);
        staff.update(s);
        therapistOf(s.getId()).ifPresent(t -> catalog.upsertTherapist(new CatalogModels.Therapist(
                t.id(), t.staffUserId(), t.employeeNo(), t.name(), t.homeStoreId(),
                t.level(), t.avatarUrl(), t.intro(), t.ratingX100(), 1,
                t.projectIds(), t.symptomIds())));

        LocalDate on = parseDate(req == null ? null : req.effectiveOn(), clock.today());
        logs.insert(new EmploymentModels.Log(
                ids.nextId(), s.getId(), EmploymentModels.REHIRE, on,
                req == null ? null : req.reason(), operator, now));
        return new EmploymentDtos.ActionResponse(
                String.valueOf(s.getId()), null,
                EmploymentModels.REHIRE, 1, on.toString(), List.of());
    }

    public EmploymentDtos.LogListResponse history(String staffIdRaw) {
        List<EmploymentModels.Log> rows = staffIdRaw == null || staffIdRaw.isBlank()
                ? logs.listRecent(50)
                : logs.listByStaff(parseId(staffIdRaw));
        List<EmploymentDtos.LogItem> items = rows.stream().map(l -> new EmploymentDtos.LogItem(
                String.valueOf(l.id()),
                String.valueOf(l.staffId()),
                staff.findById(l.staffId()).map(StaffUser::getName).orElse("—"),
                l.action(),
                l.effectiveOn() == null ? null : l.effectiveOn().toString(),
                l.reason(),
                l.createdAt() == null ? null : ISO.format(l.createdAt()))).toList();
        return new EmploymentDtos.LogListResponse(items);
    }

    /**
     * 新技师默认接本店在售的全部项目。上来就限定项目更像是运营决策，
     * 而不是入职这一步该替人做的判断；不接的项目后面在技师管理里去掉。
     */
    private String createTherapist(StaffUser s, List<Long> storeIds) {
        if (storeIds.isEmpty()) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "技师入职必须指定门店");
        }
        long homeStoreId = storeIds.getFirst();
        List<Long> projectIds = catalog.listStoreProjects().stream()
                .filter(sp -> sp.storeId() == homeStoreId && sp.status() == 1)
                .map(CatalogModels.StoreProject::projectId)
                .toList();
        List<Long> symptomIds = catalog.listSymptoms().stream()
                .filter(sy -> sy.status() == 1)
                .map(CatalogModels.Symptom::id)
                .toList();
        long id = ids.nextId();
        catalog.upsertTherapist(new CatalogModels.Therapist(
                id, s.getId(), employeeNo(id), s.getName(), homeStoreId,
                "JUNIOR", null, null, 0, 1, projectIds, symptomIds));
        return String.valueOf(id);
    }

    private static String employeeNo(long therapistId) {
        String tail = String.valueOf(therapistId);
        return "T" + tail.substring(Math.max(0, tail.length() - 6));
    }

    private List<EmploymentDtos.BlockedOrder> liveOrders(long therapistId) {
        LocalDate today = clock.today();
        return occupy.listOrdersByTherapist(therapistId, today, today.plusDays(LOOKAHEAD_DAYS))
                .stream()
                .filter(o -> LIVE.contains(o.status()))
                .map(o -> new EmploymentDtos.BlockedOrder(
                        String.valueOf(o.id()), o.orderNo(), o.serviceDate().toString(),
                        SlotTimes.toTime(o.startSlotNo()).format(HM), o.status()))
                .toList();
    }

    private Optional<CatalogModels.Therapist> therapistOf(long staffId) {
        return catalog.listTherapists().stream().filter(t -> t.staffUserId() == staffId).findFirst();
    }

    private StaffUser requireStaff(String raw) {
        return staff.findById(parseId(raw))
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "员工不存在"));
    }

    private static List<Long> parseStoreIds(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream().filter(s -> s != null && !s.isBlank()).map(Long::parseLong).toList();
    }

    private static LocalDate parseDate(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "effectiveOn 格式应为 YYYY-MM-DD");
        }
    }

    private static long parseId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "id 无效");
        }
    }
}
