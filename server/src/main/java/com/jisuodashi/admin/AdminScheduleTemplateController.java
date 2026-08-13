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
@RequestMapping("/api/v1/a/schedule-templates")
public class AdminScheduleTemplateController {

    private final AdminCatalogService catalog;

    public AdminScheduleTemplateController(AdminCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    @StoreScoped
    @RequirePerm("schedule:write")
    public ApiResponse<AdminDtos.Page<AdminDtos.TemplateItem>> list() {
        return ApiResponse.ok(catalog.listTemplates());
    }

    @GetMapping("/{id}")
    @StoreScoped
    @RequirePerm("schedule:write")
    public ApiResponse<AdminDtos.TemplateItem> get(@PathVariable String id) {
        return ApiResponse.ok(catalog.getTemplate(parseId(id)));
    }

    @PostMapping
    @StoreScoped
    @RequirePerm("schedule:write")
    public ResponseEntity<ApiResponse<AdminDtos.TemplateItem>> create(
            @RequestBody AdminDtos.TemplateUpsertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(catalog.createTemplate(request)));
    }

    @PutMapping("/{id}")
    @StoreScoped
    @RequirePerm("schedule:write")
    public ApiResponse<AdminDtos.TemplateItem> update(
            @PathVariable String id, @RequestBody AdminDtos.TemplateUpsertRequest request) {
        return ApiResponse.ok(catalog.updateTemplate(parseId(id), request));
    }

    @DeleteMapping("/{id}")
    @StoreScoped
    @RequirePerm("schedule:write")
    public ApiResponse<Void> delete(@PathVariable String id) {
        catalog.deleteTemplate(parseId(id));
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
