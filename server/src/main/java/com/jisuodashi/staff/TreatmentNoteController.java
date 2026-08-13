package com.jisuodashi.staff;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ApiResponse;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.rbac.Audited;
import com.jisuodashi.rbac.RbacDtos;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/t/orders")
public class TreatmentNoteController {

    private final TreatmentNoteService notes;

    public TreatmentNoteController(TreatmentNoteService notes) {
        this.notes = notes;
    }

    @GetMapping("/{orderId}/notes")
    @Audited(action = "NOTE_READ", resourceType = "TREATMENT_NOTE")
    public ApiResponse<RbacDtos.TreatmentNoteList> notes(@PathVariable String orderId) {
        try {
            return ApiResponse.ok(notes.listForOrder(Long.parseLong(orderId)));
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "orderId 无效");
        }
    }

    @PostMapping("/{orderId}/notes")
    @Audited(action = "NOTE_WRITE", resourceType = "TREATMENT_NOTE")
    public ApiResponse<StaffDtos.AppendNoteResponse> append(
            @PathVariable String orderId,
            @Valid @RequestBody StaffDtos.AppendNoteRequest request) {
        return ApiResponse.ok(notes.append(orderId, request));
    }
}
