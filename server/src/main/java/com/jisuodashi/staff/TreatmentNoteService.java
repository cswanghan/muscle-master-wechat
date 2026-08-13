package com.jisuodashi.staff;

import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.auth.Customer;
import com.jisuodashi.auth.CustomerRepository;
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

import java.time.Instant;
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
    private final CustomerRepository customers;
    private final SnowflakeIdGenerator ids;
    private final AppClock clock;

    public TreatmentNoteService(
            TreatmentNoteRepository notes,
            StaffTherapistLookup therapists,
            SlotOccupyService occupy,
            CustomerRepository customers,
            SnowflakeIdGenerator ids,
            AppClock clock) {
        this.notes = notes;
        this.therapists = therapists;
        this.occupy = occupy;
        this.customers = customers;
        this.ids = ids;
        this.clock = clock;
    }

    public RbacDtos.TreatmentNoteList listForOrder(long orderId) {
        JwtPrincipal principal = requireTherapistToken();
        CatalogModels.Therapist therapist = therapists.requireTherapist(principal);
        List<TreatmentNote> rows = notes.findByOrderId(orderId);
        BookingOrderRef order = occupy.findOrderById(orderId);
        boolean served = served(principal, therapist.id(), order, rows, orderId);
        if (order != null) {
            AuditHints.setStoreId(order.storeId());
            AuditHints.setResourceId(order.id());
        } else if (!rows.isEmpty()) {
            AuditHints.setStoreId(rows.getFirst().storeId());
            AuditHints.setResourceId(rows.getFirst().id());
        }
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
        return new RbacDtos.TreatmentNoteList(items, consented(order));
    }

    public StaffDtos.ConsentResponse consent(String orderIdRaw) {
        JwtPrincipal principal = requireTherapistToken();
        CatalogModels.Therapist therapist = therapists.requireTherapist(principal);
        long orderId = StaffOrderService.parseOrderId(orderIdRaw);
        BookingOrderRef order = occupy.findOrderById(orderId);
        if (order == null || order.therapistId() != therapist.id()) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "订单不存在");
        }
        Instant at = stampConsent(order.customerId());
        AuditHints.setStoreId(order.storeId());
        AuditHints.setResourceId(order.id());
        return new StaffDtos.ConsentResponse(
                String.valueOf(order.id()), true, at == null ? null : ISO.format(at));
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
        if (request != null && Boolean.TRUE.equals(request.consent())) {
            stampConsent(order.customerId());
        }
        if (!consented(order)) {
            throw new ApiException(ErrorCodes.FORBIDDEN, "未签署理疗记录知情同意");
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

    private boolean served(
            JwtPrincipal principal,
            long therapistId,
            BookingOrderRef order,
            List<TreatmentNote> rows,
            long orderId) {
        if (order != null && order.therapistId() == therapistId) {
            return true;
        }
        if (notes.findLatestServiceRecord(orderId).map(r -> r.therapistId() == therapistId).orElse(false)) {
            return true;
        }
        return rows.stream().anyMatch(n ->
                n.authorStaffId() == principal.subjectId() || n.therapistId() == therapistId);
    }

    private boolean consented(BookingOrderRef order) {
        if (order == null) {
            return false;
        }
        return customers.findById(order.customerId())
                .map(Customer::getTreatmentConsentAt)
                .isPresent();
    }

    private Instant stampConsent(long customerId) {
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> new ApiException(ErrorCodes.NOT_FOUND, "客户不存在"));
        if (customer.getTreatmentConsentAt() == null) {
            Instant now = clock.instant();
            customer.setTreatmentConsentAt(now);
            customer.setUpdatedAt(now);
            customers.update(customer);
        }
        return customer.getTreatmentConsentAt();
    }

    private static JwtPrincipal requireTherapistToken() {
        JwtPrincipal principal = AuthContext.requireStaff();
        if (principal.typ() != TokenType.T) {
            throw new ApiException(ErrorCodes.FORBIDDEN, "无功能权限");
        }
        return principal;
    }
}
