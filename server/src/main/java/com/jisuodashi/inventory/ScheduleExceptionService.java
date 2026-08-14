package com.jisuodashi.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.auth.HumanTask;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.inventory.ScheduleExceptionStore.ScheduleExceptionRow;
import com.jisuodashi.rbac.StoreScope;
import com.jisuodashi.rbac.StoreScopeContext;
import com.jisuodashi.workflow.HumanTaskQueue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The only implementation of leave approval (§2.3). {@code /a/schedule-exceptions/{id}/approve}
 * and {@code /f/human-tasks/{id}/approve} both land on {@link #approve(long)} — there is no
 * second state machine.
 *
 * <p>Apply writes {@code schedule_exception(PENDING)} and its {@code human_task(LEAVE_APPROVE)}
 * in one transaction. Approve counts busy slots in the requested half-open range, rejects with
 * {@code 40906} if any, then flips {@code FREE → REST} and closes the task.
 */
@Service
public class ScheduleExceptionService {

    public static final String TASK_LEAVE_APPROVE = "LEAVE_APPROVE";
    public static final String BIZ_KEY_PREFIX = "leave:";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ScheduleExceptionStore exceptions;
    private final HumanTaskQueue tasks;
    private final SlotOccupyService occupy;
    private final SlotOccupyStore slots;
    private final SnowflakeIdGenerator ids;
    private final AppClock clock;
    private final TransactionTemplate tx;

    @Autowired
    public ScheduleExceptionService(
            ScheduleExceptionStore exceptions,
            HumanTaskQueue tasks,
            SlotOccupyService occupy,
            SlotOccupyStore slots,
            SnowflakeIdGenerator ids,
            AppClock clock,
            PlatformTransactionManager txManager
    ) {
        this(exceptions, tasks, occupy, slots, ids, clock, new TransactionTemplate(txManager));
    }

    ScheduleExceptionService(
            ScheduleExceptionStore exceptions,
            HumanTaskQueue tasks,
            SlotOccupyService occupy,
            SlotOccupyStore slots,
            SnowflakeIdGenerator ids,
            AppClock clock,
            TransactionTemplate tx
    ) {
        this.exceptions = exceptions;
        this.tasks = tasks;
        this.occupy = occupy;
        this.slots = slots;
        this.ids = ids;
        this.clock = clock;
        this.tx = tx;
    }

    /** Create a PENDING exception + its OPEN {@code LEAVE_APPROVE} task in one transaction. */
    public ScheduleExceptionDtos.ExceptionView apply(ScheduleExceptionDtos.ApplyRequest request) {
        if (request == null) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "请求不能为空");
        }
        long therapistId = parseId(request.therapistId(), "therapistId");
        LocalDate date = parseDate(request.date(), "date");
        String type = request.type() == null || request.type().isBlank()
                ? ScheduleExceptionStore.TYPE_LEAVE
                : request.type().trim().toUpperCase(Locale.ROOT);
        if (!ScheduleExceptionStore.TYPE_LEAVE.equals(type)
                && !ScheduleExceptionStore.TYPE_ADJUST.equals(type)
                && !ScheduleExceptionStore.TYPE_SUPPORT.equals(type)) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "type 仅支持 LEAVE / ADJUST / SUPPORT");
        }
        LocalTime start = parseTime(request.startTime(), "startTime");
        LocalTime end = parseTime(request.endTime(), "endTime");
        if (start != null && end != null && !start.isBefore(end)) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "startTime 须早于 endTime");
        }
        long storeId = resolveStoreId(request.storeId(), therapistId);
        StoreScope scope = StoreScopeContext.get();
        if (scope != null) {
            scope.assertContains(storeId);
        }

        long id = ids.nextId();
        LocalDateTime now = clock.now();
        JwtPrincipal principal = AuthContext.get();
        Long createdBy = principal == null ? null : principal.staffId();
        ScheduleExceptionRow row = new ScheduleExceptionRow(
                id, therapistId, storeId, date, type, start, end,
                trimOrNull(request.reason()), ScheduleExceptionStore.STATUS_PENDING,
                createdBy, now, now);

        return inTx(() -> {
            exceptions.insert(row);
            if (ScheduleExceptionStore.TYPE_LEAVE.equals(type)) {
                tasks.insert(leaveTask(row));
            }
            return view(row, taskKeyOf(row), null);
        });
    }

    /**
     * Approve by exception id. Idempotent: an already APPROVED row replays without touching slots.
     *
     * @return the approved row, {@code restSlots} = slots flipped FREE → REST
     */
    public ScheduleExceptionDtos.ExceptionView approve(long exceptionId) {
        JwtPrincipal principal = AuthContext.requireStaff();
        return inTx(() -> {
            ScheduleExceptionRow row = lockOrThrow(exceptionId);
            if (ScheduleExceptionStore.STATUS_APPROVED.equals(row.status())) {
                return view(row, taskKeyOf(row), 0);
            }
            if (!ScheduleExceptionStore.STATUS_PENDING.equals(row.status())) {
                throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "非法状态转移");
            }
            int rest = 0;
            if (ScheduleExceptionStore.TYPE_LEAVE.equals(row.type())) {
                long storeId = row.storeId() == null
                        ? resolveStoreId(null, row.therapistId())
                        : row.storeId();
                rest = occupy.applyLeaveRest(
                        row.therapistId(), row.exceptDate(),
                        fromSlotNo(row.startTime()), toSlotNoExclusive(row.endTime()), storeId);
            }
            LocalDateTime now = clock.now();
            if (exceptions.casStatus(exceptionId, ScheduleExceptionStore.STATUS_PENDING,
                    ScheduleExceptionStore.STATUS_APPROVED, now) == 0) {
                throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "非法状态转移");
            }
            closeTask(row, HumanTaskQueue.STATUS_DONE, principal.staffId(), now);
            return view(row.withStatus(ScheduleExceptionStore.STATUS_APPROVED, now),
                    taskKeyOf(row), rest);
        });
    }

    /** Reject: the exception goes REJECTED and the task is IGNORED. No slot is touched. */
    public ScheduleExceptionDtos.ExceptionView reject(long exceptionId) {
        JwtPrincipal principal = AuthContext.requireStaff();
        return inTx(() -> {
            ScheduleExceptionRow row = lockOrThrow(exceptionId);
            if (ScheduleExceptionStore.STATUS_REJECTED.equals(row.status())) {
                return view(row, taskKeyOf(row), null);
            }
            if (!ScheduleExceptionStore.STATUS_PENDING.equals(row.status())) {
                throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "非法状态转移");
            }
            LocalDateTime now = clock.now();
            if (exceptions.casStatus(exceptionId, ScheduleExceptionStore.STATUS_PENDING,
                    ScheduleExceptionStore.STATUS_REJECTED, now) == 0) {
                throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "非法状态转移");
            }
            closeTask(row, HumanTaskQueue.STATUS_IGNORED, principal.staffId(), now);
            return view(row.withStatus(ScheduleExceptionStore.STATUS_REJECTED, now),
                    taskKeyOf(row), null);
        });
    }

    /** Entry point for {@code /f/human-tasks/{id}/approve}: reads {@code detail.exceptionId}. */
    public ScheduleExceptionDtos.ExceptionView approveTask(HumanTask task) {
        return approve(exceptionIdOf(task));
    }

    /** Entry point for {@code /f/human-tasks/{id}/deny}. */
    public ScheduleExceptionDtos.ExceptionView rejectTask(HumanTask task) {
        return reject(exceptionIdOf(task));
    }

    public ScheduleExceptionDtos.ListResponse list(String fromRaw, String toRaw, String status) {
        StoreScope scope = StoreScopeContext.get();
        List<Long> storeIds = scope == null || scope.all() ? List.of() : scope.storeIds();
        LocalDate from = fromRaw == null || fromRaw.isBlank() ? null : parseDate(fromRaw, "from");
        LocalDate to = toRaw == null || toRaw.isBlank() ? null : parseDate(toRaw, "to");
        String wanted = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        List<ScheduleExceptionDtos.ExceptionView> items = exceptions.list(storeIds, from, to, wanted).stream()
                .map(row -> view(row, taskKeyOf(row), null))
                .toList();
        return new ScheduleExceptionDtos.ListResponse(items);
    }

    /** {@code detail.exceptionId}, falling back to the {@code leave:{id}} biz key. */
    public static long exceptionIdOf(HumanTask task) {
        if (task == null) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "任务不存在");
        }
        String detail = task.getDetail();
        if (detail != null && !detail.isBlank()) {
            try {
                JsonNode node = JSON.readTree(detail).get("exceptionId");
                if (node != null && node.canConvertToLong()) {
                    return node.asLong();
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                // fall through to biz_key
            }
        }
        String bizKey = task.getBizKey();
        if (bizKey != null && bizKey.startsWith(BIZ_KEY_PREFIX)) {
            try {
                return Long.parseLong(bizKey.substring(BIZ_KEY_PREFIX.length()));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        throw new ApiException(ErrorCodes.BAD_REQUEST, "任务缺少 exceptionId");
    }

    private HumanTask leaveTask(ScheduleExceptionRow row) {
        HumanTask task = new HumanTask();
        task.setId(ids.nextId());
        task.setTaskType(TASK_LEAVE_APPROVE);
        task.setBizKey(BIZ_KEY_PREFIX + row.id());
        task.setTitle("请假审批 " + row.exceptDate() + " 技师 " + row.therapistId());
        task.setDetail("{\"exceptionId\":" + row.id() + "}");
        task.setStatus(HumanTaskQueue.STATUS_OPEN);
        task.setStoreId(row.storeId());
        task.setCreatedAt(clock.instant());
        return task;
    }

    private void closeTask(ScheduleExceptionRow row, String status, Long staffId, LocalDateTime now) {
        HumanTask task = tasks.lockByBizKey(BIZ_KEY_PREFIX + row.id());
        if (task == null || !HumanTaskQueue.STATUS_OPEN.equals(task.getStatus())) {
            return;
        }
        task.setStatus(status);
        task.setResolvedBy(staffId);
        task.setResolvedAt(now.atZone(AppClock.SHANGHAI).toInstant());
        tasks.update(task);
    }

    private ScheduleExceptionRow lockOrThrow(long exceptionId) {
        ScheduleExceptionRow row = exceptions.lockById(exceptionId);
        if (row == null) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "排班异常单不存在");
        }
        StoreScope scope = StoreScopeContext.get();
        if (scope != null && row.storeId() != null) {
            scope.assertContains(row.storeId());
        }
        return row;
    }

    private long resolveStoreId(String raw, long therapistId) {
        if (raw != null && !raw.isBlank()) {
            return parseId(raw, "storeId");
        }
        SlotOccupyStore.TherapistRef therapist = slots.loadTherapist(therapistId);
        if (therapist == null) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "技师不存在");
        }
        return therapist.homeStoreId();
    }

    /** {@code start_time} null = 全日，等价于 business_start（营业时段外没有 slot 行）。 */
    static int fromSlotNo(LocalTime start) {
        return start == null ? 0 : SlotTimes.toSlotNo(start);
    }

    /** Half-open upper bound; null = 全日。 */
    static int toSlotNoExclusive(LocalTime end) {
        return end == null ? SlotTimes.SLOTS_PER_DAY : SlotTimes.toSlotNo(end);
    }

    private static ScheduleExceptionDtos.ExceptionView view(
            ScheduleExceptionRow row, String taskId, Integer restSlots) {
        return new ScheduleExceptionDtos.ExceptionView(
                String.valueOf(row.id()),
                String.valueOf(row.therapistId()),
                row.storeId() == null ? null : String.valueOf(row.storeId()),
                row.exceptDate().toString(),
                row.type(),
                row.startTime() == null ? null : row.startTime().toString(),
                row.endTime() == null ? null : row.endTime().toString(),
                row.reason(),
                row.status(),
                taskId,
                restSlots);
    }

    private String taskKeyOf(ScheduleExceptionRow row) {
        if (!ScheduleExceptionStore.TYPE_LEAVE.equals(row.type())) {
            return null;
        }
        return BIZ_KEY_PREFIX + row.id();
    }

    private <T> T inTx(Supplier<T> work) {
        return runTx(() -> {
            exceptions.beginWork();
            tasks.beginWork();
            try {
                T result = work.get();
                exceptions.commitWork();
                tasks.commitWork();
                return result;
            } catch (RuntimeException ex) {
                exceptions.rollbackWork();
                tasks.rollbackWork();
                throw ex;
            }
        });
    }

    private <T> T runTx(Supplier<T> work) {
        if (tx == null) {
            return work.get();
        }
        return tx.execute(status -> work.get());
    }

    private static String trimOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static long parseId(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, field + " 不能为空");
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, field + " 无效");
        }
    }

    private static LocalDate parseDate(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, field + " 不能为空");
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, field + " 无效");
        }
    }

    private static LocalTime parseTime(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        LocalTime time;
        try {
            time = LocalTime.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, field + " 无效");
        }
        if (time.getMinute() % 15 != 0 || time.getSecond() != 0) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, field + " 必须对齐 15 分钟");
        }
        return time;
    }
}
