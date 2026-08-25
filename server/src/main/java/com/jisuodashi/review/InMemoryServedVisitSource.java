package com.jisuodashi.review;

import com.jisuodashi.staff.TreatmentNoteRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/** dev 侧服务段来源：{@code InMemoryTreatmentNoteRepository} 里由 START/COMPLETE_SERVICE 写下的行。 */
@Component
@Profile("dev")
public class InMemoryServedVisitSource implements ServedVisitSource {

    private final TreatmentNoteRepository notes;

    public InMemoryServedVisitSource(TreatmentNoteRepository notes) {
        this.notes = notes;
    }

    @Override
    public List<ServedVisit> listCompletedVisits() {
        return notes.listAllServiceRecords().stream()
                .filter(r -> r.endedAt() != null && r.startedAt() != null)
                .map(r -> new ServedVisit(r.therapistId(), r.customerId(), r.startedAt()))
                .toList();
    }
}
