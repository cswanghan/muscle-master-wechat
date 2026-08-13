package com.jisuodashi.admin;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ApiResponse;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.rbac.RbacDtos;
import com.jisuodashi.rbac.RequirePerm;
import com.jisuodashi.rbac.ScopedStoreService;
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
@RequestMapping("/api/v1/a/stores")
public class AdminStoreController {

    private final ScopedStoreService stores;
    private final SnowflakeIdGenerator ids;

    public AdminStoreController(ScopedStoreService stores, SnowflakeIdGenerator ids) {
        this.stores = stores;
        this.ids = ids;
    }

    @GetMapping
    @StoreScoped
    @RequirePerm("catalog:store")
    public ApiResponse<RbacDtos.StoreListResponse> list() {
        return ApiResponse.ok(stores.list());
    }

    @GetMapping("/{id}")
    @StoreScoped
    @RequirePerm("catalog:store")
    public ApiResponse<RbacDtos.StoreItem> get(@PathVariable String id) {
        return ApiResponse.ok(stores.get(parseId(id)));
    }

    @PostMapping
    @StoreScoped
    @RequirePerm("catalog:store")
    public ResponseEntity<ApiResponse<RbacDtos.StoreItem>> create(@RequestBody RbacDtos.StoreUpsertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(stores.create(ids.nextId(), request)));
    }

    @PutMapping("/{id}")
    @StoreScoped
    @RequirePerm("catalog:store")
    public ApiResponse<RbacDtos.StoreItem> update(
            @PathVariable String id, @RequestBody RbacDtos.StoreUpsertRequest request) {
        return ApiResponse.ok(stores.update(parseId(id), request));
    }

    @DeleteMapping("/{id}")
    @StoreScoped
    @RequirePerm("catalog:store")
    public ApiResponse<Void> delete(@PathVariable String id) {
        stores.delete(parseId(id));
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/status")
    @StoreScoped
    @RequirePerm("catalog:write")
    public ApiResponse<RbacDtos.StoreItem> status(
            @PathVariable String id, @RequestBody RbacDtos.StoreStatusRequest request) {
        return ApiResponse.ok(stores.updateStatus(parseId(id), request.status()));
    }

    private static long parseId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "id 无效");
        }
    }
}
