package com.jisuodashi.catalog;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ApiResponse;
import com.jisuodashi.common.ErrorCodes;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/c")
public class CatalogController {

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/stores")
    public ApiResponse<CatalogDtos.Page<CatalogDtos.StoreListItem>> stores(
            @RequestParam(required = false) Double lng,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(catalog.listStores(lng, lat, cursor, limit));
    }

    @GetMapping("/stores/{id}")
    public ApiResponse<CatalogDtos.StoreDetail> store(@PathVariable String id) {
        return ApiResponse.ok(catalog.getStore(parseId(id)));
    }

    @GetMapping("/therapists")
    public ApiResponse<CatalogDtos.Page<CatalogDtos.TherapistItem>> therapists(
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) Long symptomId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(catalog.listTherapists(storeId, symptomId, cursor, limit));
    }

    @GetMapping("/projects")
    public ApiResponse<CatalogDtos.Page<CatalogDtos.ProjectSummary>> projects(
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) Long symptomId) {
        return ApiResponse.ok(catalog.listProjects(storeId, symptomId));
    }

    @GetMapping("/symptoms")
    public ApiResponse<CatalogDtos.Page<CatalogDtos.SymptomItem>> symptoms() {
        return ApiResponse.ok(catalog.listSymptoms());
    }

    @GetMapping("/symptoms/{id}/projects")
    public ApiResponse<CatalogDtos.SymptomProjects> symptomProjects(@PathVariable String id) {
        return ApiResponse.ok(catalog.projectsForSymptom(parseId(id)));
    }

    private static long parseId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "id 无效");
        }
    }
}
