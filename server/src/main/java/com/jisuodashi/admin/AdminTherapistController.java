package com.jisuodashi.admin;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ApiResponse;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.rbac.RequirePerm;
import com.jisuodashi.rbac.StoreScoped;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/a/therapists")
public class AdminTherapistController {

    private final AdminCatalogService catalog;

    public AdminTherapistController(AdminCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    @StoreScoped
    @RequirePerm("catalog:therapist")
    public ApiResponse<AdminDtos.Page<AdminDtos.TherapistItem>> list() {
        return ApiResponse.ok(catalog.listTherapists());
    }

    @GetMapping("/{id}")
    @StoreScoped
    @RequirePerm("catalog:therapist")
    public ApiResponse<AdminDtos.TherapistItem> get(@PathVariable String id) {
        return ApiResponse.ok(catalog.getTherapist(parseId(id)));
    }

    @PostMapping
    @StoreScoped
    @RequirePerm("catalog:therapist")
    public ResponseEntity<ApiResponse<AdminDtos.TherapistItem>> create(
            @RequestBody AdminDtos.TherapistUpsertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(catalog.createTherapist(request)));
    }

    @PutMapping("/{id}")
    @StoreScoped
    @RequirePerm("catalog:therapist")
    public ApiResponse<AdminDtos.TherapistItem> update(
            @PathVariable String id, @RequestBody AdminDtos.TherapistUpsertRequest request) {
        return ApiResponse.ok(catalog.updateTherapist(parseId(id), request));
    }

    @DeleteMapping("/{id}")
    @StoreScoped
    @RequirePerm("catalog:therapist")
    public ApiResponse<Void> delete(@PathVariable String id) {
        catalog.deleteTherapist(parseId(id));
        return ApiResponse.ok(null);
    }

    private static long parseId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "id 无效");
        }
    }
}
