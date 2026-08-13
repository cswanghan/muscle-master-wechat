package com.jisuodashi.admin;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ApiResponse;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.rbac.RbacDtos;
import com.jisuodashi.rbac.RequirePerm;
import com.jisuodashi.rbac.ScopedStoreService;
import com.jisuodashi.rbac.StoreScoped;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/a/stores")
public class AdminStoreController {

    private final ScopedStoreService stores;

    public AdminStoreController(ScopedStoreService stores) {
        this.stores = stores;
    }

    @GetMapping
    @StoreScoped
    @RequirePerm("catalog:store")
    public ApiResponse<RbacDtos.StoreListResponse> list() {
        return ApiResponse.ok(stores.list());
    }

    @PostMapping("/{id}/status")
    @StoreScoped
    @RequirePerm("catalog:write")
    public ApiResponse<RbacDtos.StoreItem> status(
            @PathVariable String id, @RequestBody RbacDtos.StoreStatusRequest request) {
        try {
            return ApiResponse.ok(stores.updateStatus(Long.parseLong(id), request.status()));
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "id 无效");
        }
    }
}
