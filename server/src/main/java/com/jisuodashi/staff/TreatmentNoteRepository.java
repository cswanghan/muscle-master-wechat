package com.jisuodashi.staff;

import java.util.List;

public interface TreatmentNoteRepository {

    List<TreatmentNote> findByOrderId(long orderId);
}
