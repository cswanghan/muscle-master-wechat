package com.jisuodashi.inventory;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static com.jisuodashi.inventory.OccupyFixtures.START_1930;
import static com.jisuodashi.inventory.OccupyFixtures.T1;
import static com.jisuodashi.inventory.OccupyFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlotOccupyIdempotencyTest {

    @Test
    void replayReturnsSameOrderAndDoesNotDoubleOccupy() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);

        LockNewResult first = service.lockNew(OccupyFixtures.cmd("idem-1", T1, START_1930));
        LockNewResult second = service.lockNew(OccupyFixtures.cmd("idem-1", T1, START_1930));

        assertThat(second.replay()).isTrue();
        assertThat(second.orderId()).isEqualTo(first.orderId());
        assertThat(second.holdId()).isEqualTo(first.holdId());
        assertThat(second.orderNo()).isEqualTo(first.orderNo());
        assertThat(store.orders).hasSize(1);
        assertThat(store.occupancies).hasSize(10);
        assertThat(store.delayedJobs).hasSize(1);
        assertThat(store.idem(SlotOccupyService.SCOPE_BOOKING, "idem-1").status).isEqualTo("DONE");
        assertThat(store.idem(SlotOccupyService.SCOPE_BOOKING, "idem-1").version).isZero();
    }

    @Test
    void finishIdempotentNeverOverwritesDone() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        LockNewResult first = service.lockNew(OccupyFixtures.cmd("idem-done", T1, START_1930));
        String body = store.idem(SlotOccupyService.SCOPE_BOOKING, "idem-done").responseBody;

        store.beginWork();
        int n = store.finishIdempotent(
                SlotOccupyService.SCOPE_BOOKING, "idem-done", 0, "{\"tamper\":true}", LocalDateTime.now());
        store.commitWork();

        assertThat(n).isZero();
        assertThat(store.idem(SlotOccupyService.SCOPE_BOOKING, "idem-done").status).isEqualTo("DONE");
        assertThat(store.idem(SlotOccupyService.SCOPE_BOOKING, "idem-done").responseBody).isEqualTo(body);
        assertThat(body).contains(String.valueOf(first.orderId())).doesNotContain("tamper");
    }

    @Test
    void processingUnexpiredIs40903() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        store.beginWork();
        store.insertIdempotency(new SlotOccupyStore.IdemInsert(
                1, SlotOccupyService.SCOPE_BOOKING, "stuck", "PROCESSING", 0, "w1",
                TODAY.atTime(19, 0), TODAY.atTime(19, 0), TODAY.atTime(19, 0).plusSeconds(30)));
        store.commitWork();

        assertThatThrownBy(() -> OccupyFixtures.service(store).lockNew(OccupyFixtures.cmd("stuck", T1, START_1930)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.LOCK_CONFLICT);
        assertThat(store.orders).isEmpty();
    }
}
