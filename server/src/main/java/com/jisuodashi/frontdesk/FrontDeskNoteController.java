package com.jisuodashi.frontdesk;

import com.jisuodashi.common.ApiResponse;
import com.jisuodashi.rbac.RbacDtos;
import com.jisuodashi.rbac.RequirePerm;
import com.jisuodashi.rbac.StoreScoped;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/f/desk-notes")
public class FrontDeskNoteController {

    private final DeskNoteService notes;

    public FrontDeskNoteController(DeskNoteService notes) {
        this.notes = notes;
    }

    @PostMapping
    @StoreScoped
    @RequirePerm("frontdesk:order:*")
    public ApiResponse<RbacDtos.DeskNoteResponse> create(@RequestBody RbacDtos.DeskNoteRequest request) {
        return ApiResponse.ok(notes.create(request));
    }
}
