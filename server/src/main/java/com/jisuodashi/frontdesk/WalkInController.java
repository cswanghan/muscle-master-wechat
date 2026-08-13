package com.jisuodashi.frontdesk;

import com.jisuodashi.common.ApiResponse;
import com.jisuodashi.rbac.RequirePerm;
import com.jisuodashi.rbac.StoreScoped;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/f/walk-ins")
public class WalkInController {

    private final FrontDeskService desk;

    public WalkInController(FrontDeskService desk) {
        this.desk = desk;
    }

    @PostMapping
    @StoreScoped
    @RequirePerm("frontdesk:order:*")
    public ResponseEntity<ApiResponse<FrontDeskDtos.WalkInResponse>> create(
            @Valid @RequestBody FrontDeskDtos.WalkInRequest request) {
        FrontDeskDtos.WalkInResponse data = desk.walkIn(request);
        HttpStatus status = data.replay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(ApiResponse.ok(data));
    }
}
