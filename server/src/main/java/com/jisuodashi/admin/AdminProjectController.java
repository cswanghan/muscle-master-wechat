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
@RequestMapping("/api/v1/a/projects")
public class AdminProjectController {

    private final AdminCatalogService catalog;

    public AdminProjectController(AdminCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    @StoreScoped
    @RequirePerm("catalog:project")
    public ApiResponse<AdminDtos.Page<AdminDtos.ProjectItem>> list() {
        return ApiResponse.ok(catalog.listProjects());
    }

    @GetMapping("/{id}")
    @StoreScoped
    @RequirePerm("catalog:project")
    public ApiResponse<AdminDtos.ProjectItem> get(@PathVariable String id) {
        return ApiResponse.ok(catalog.getProject(parseId(id)));
    }

    @PostMapping
    @StoreScoped
    @RequirePerm("catalog:project")
    public ResponseEntity<ApiResponse<AdminDtos.ProjectItem>> create(
            @RequestBody AdminDtos.ProjectUpsertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(catalog.createProject(request)));
    }

    @PutMapping("/{id}")
    @StoreScoped
    @RequirePerm("catalog:project")
    public ApiResponse<AdminDtos.ProjectItem> update(
            @PathVariable String id, @RequestBody AdminDtos.ProjectUpsertRequest request) {
        return ApiResponse.ok(catalog.updateProject(parseId(id), request));
    }

    @DeleteMapping("/{id}")
    @StoreScoped
    @RequirePerm("catalog:project")
    public ApiResponse<Void> delete(@PathVariable String id) {
        catalog.deleteProject(parseId(id));
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
