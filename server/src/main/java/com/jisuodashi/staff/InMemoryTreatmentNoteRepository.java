package com.jisuodashi.staff;

import com.jisuodashi.auth.DemoStaffIds;
import com.jisuodashi.rbac.RbacDemoIds;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
@Profile("dev")
public class InMemoryTreatmentNoteRepository implements TreatmentNoteRepository {

    private final List<TreatmentNote> notes = List.of(new TreatmentNote(
            RbacDemoIds.NOTE_ID,
            RbacDemoIds.NOTE_ORDER,
            RbacDemoIds.STORE,
            RbacDemoIds.THERAPIST_LIN,
            DemoStaffIds.T1,
            "肩颈放松，客户反馈酸胀减轻",
            Instant.parse("2026-08-14T04:00:00Z")));

    @Override
    public List<TreatmentNote> findByOrderId(long orderId) {
        return notes.stream().filter(n -> n.orderId() == orderId).toList();
    }
}
