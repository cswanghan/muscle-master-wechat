package com.jisuodashi.frontdesk;

import com.jisuodashi.common.ApiResponse;
import com.jisuodashi.rbac.RbacDtos;
import com.jisuodashi.rbac.RequirePerm;
import com.jisuodashi.rbac.ScopedStoreService;
import com.jisuodashi.rbac.StoreScoped;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/f/stores")
public class FrontStoreController {

    private final ScopedStoreService stores;

    public FrontStoreController(ScopedStoreService stores) {
        this.stores = stores;
    }

    @GetMapping
    @StoreScoped
    @RequirePerm("order:list")
    public ApiResponse<RbacDtos.StoreListResponse> list() {
        return ApiResponse.ok(stores.list());
    }
}
