package com.jisuodashi.staff;

import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.TokenType;
import com.jisuodashi.catalog.CatalogModels;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import com.jisuodashi.rbac.AuditHints;
import com.jisuodashi.rbac.RbacDtos;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

@Service
public class TreatmentNoteService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private static final int MAX_CONTENT = 2000;
    private static final Set<String> NOTEABLE = Set.of("CHECKED_IN", "IN_SERVICE", "COMPLETED");

    private final TreatmentNoteRepository notes;
    private final StaffTherapistLookup therapists;
    private final SlotOccupyService occupy;
    private final SnowflakeIdGenerator ids;
    private final AppClock clock;

    public TreatmentNoteService(
            TreatmentNoteRepository notes,
            StaffTherapistLookup therapists,
            SlotOccupyService occupy,
            SnowflakeIdGenerator ids,
            AppClock clock) {
        this.notes = notes;
        this.therapists = therapists;
        this.occupy = occupy;
        this.ids = ids;
        this.clock = clock;
    }

    public RbacDtos.TreatmentNoteList listForOrder(long orderId) {
        JwtPrincipal principal = requireTherapistToken();
        List<TreatmentNote> rows = notes.findByOrderId(orderId);
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "理疗记录不存在");
        }
        AuditHints.setStoreId(rows.getFirst().storeId());
        AuditHints.setResourceId(rows.getFirst().id());
        Long therapistId = therapists.requireTherapist(principal).id();
        boolean served = rows.stream().anyMatch(n ->
                n.authorStaffId() == principal.subjectId()
                        || n.therapistId() == therapistId);
        if (!served) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "理疗记录不存在");
        }
        List<RbacDtos.TreatmentNoteItem> items = rows.stream()
                .map(n -> new RbacDtos.TreatmentNoteItem(
                        String.valueOf(n.id()),
                        String.valueOf(n.orderId()),
                        n.content(),
                        ISO.format(n.createdAt())))
                .toList();
        return new RbacDtos.TreatmentNoteList(items);
    }

    public StaffDtos.AppendNoteResponse append(String orderIdRaw, StaffDtos.AppendNoteRequest request) {
        JwtPrincipal principal = requireTherapistToken();
        CatalogModels.Therapist therapist = therapists.requireTherapist(principal);
        long orderId = StaffOrderService.parseOrderId(orderIdRaw);
        String content = request == null ? null : request.content();
        if (content == null || content.isBlank()) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "content 不能为空");
        }
        String trimmed = content.trim();
        if (trimmed.length() > MAX_CONTENT) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "content 过长");
        }
        BookingOrderRef order = occupy.findOrderById(orderId);
        if (order == null || order.therapistId() != therapist.id()) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "订单不存在");
        }
        if (!NOTEABLE.contains(order.status())) {
            throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "当前状态不可写理疗记录");
        }
        notes.ensureServiceRecord(
                ids.nextId(), orderId, therapist.id(), order.customerId(), order.storeId(), clock.instant());
        TreatmentNote saved = notes.insert(new TreatmentNote(
                ids.nextId(),
                orderId,
                order.storeId(),
                therapist.id(),
                principal.subjectId(),
                trimmed,
                clock.instant()));
        AuditHints.setStoreId(order.storeId());
        AuditHints.setResourceId(saved.id());
        return new StaffDtos.AppendNoteResponse(
                String.valueOf(saved.id()),
                String.valueOf(saved.orderId()),
                saved.content(),
                ISO.format(saved.createdAt()));
    }

    private static JwtPrincipal requireTherapistToken() {
        JwtPrincipal principal = AuthContext.requireStaff();
        if (principal.typ() != TokenType.T) {
            throw new ApiException(ErrorCodes.FORBIDDEN, "无功能权限");
        }
        return principal;
    }
}
