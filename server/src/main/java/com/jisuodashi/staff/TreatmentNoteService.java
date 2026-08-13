package com.jisuodashi.staff;

import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.TokenType;
import com.jisuodashi.catalog.CatalogModels;
import com.jisuodashi.catalog.CatalogRepository;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.rbac.DataScopeType;
import com.jisuodashi.rbac.RbacDtos;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class TreatmentNoteService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final TreatmentNoteRepository notes;
    private final CatalogRepository catalog;

    public TreatmentNoteService(TreatmentNoteRepository notes, CatalogRepository catalog) {
        this.notes = notes;
        this.catalog = catalog;
    }

    public RbacDtos.TreatmentNoteList listForOrder(long orderId) {
        JwtPrincipal principal = AuthContext.requireStaff();
        List<TreatmentNote> rows = notes.findByOrderId(orderId);
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "理疗记录不存在");
        }
        if (principal.typ() == TokenType.T || DataScopeType.SELF == DataScopeType.parse(principal.scopeType())) {
            Long therapistId = catalog.listTherapists().stream()
                    .filter(t -> t.staffUserId() == principal.subjectId())
                    .map(CatalogModels.Therapist::id)
                    .findFirst()
                    .orElse(null);
            if (therapistId == null || rows.stream().anyMatch(n -> n.therapistId() != therapistId)) {
                throw new ApiException(ErrorCodes.DATA_SCOPE, "数据域拒绝");
            }
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
}
