package com.jisuodashi.frontdesk;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.rbac.RbacDtos;
import com.jisuodashi.rbac.StoreScopeContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class DeskNoteService {

    private final SnowflakeIdGenerator ids;
    private final List<RbacDtos.DeskNoteResponse> notes = new CopyOnWriteArrayList<>();

    public DeskNoteService(SnowflakeIdGenerator ids) {
        this.ids = ids;
    }

    public RbacDtos.DeskNoteResponse create(RbacDtos.DeskNoteRequest request) {
        if (request == null || request.storeId() == null || request.storeId().isBlank()) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "storeId 不能为空");
        }
        long storeId;
        try {
            storeId = Long.parseLong(request.storeId());
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "storeId 无效");
        }
        StoreScopeContext.require().assertContains(storeId);
        String content = request.content() == null ? "" : request.content();
        RbacDtos.DeskNoteResponse note =
                new RbacDtos.DeskNoteResponse(String.valueOf(ids.nextId()), String.valueOf(storeId), content);
        notes.add(note);
        return note;
    }

    public List<RbacDtos.DeskNoteResponse> all() {
        return new ArrayList<>(notes);
    }

    public void clear() {
        notes.clear();
    }
}
