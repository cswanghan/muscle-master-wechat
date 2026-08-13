package com.jisuodashi.inventory;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.jisuodashi.inventory.OccupyFixtures.START_1930;
import static com.jisuodashi.inventory.OccupyFixtures.START_2000;
import static com.jisuodashi.inventory.OccupyFixtures.T1;
import static com.jisuodashi.inventory.OccupyFixtures.T2;
import static com.jisuodashi.inventory.OccupyFixtures.T3;
import static org.assertj.core.api.Assertions.assertThat;

class SlotOccupyConcurrencyTest {

    @Test
    void threeTherapistsTwoBedsExactlyTwoSucceed() throws Exception {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore(2);
        SlotOccupyService service = OccupyFixtures.service(store);
        LockReport report = race(service, List.of(
                OccupyFixtures.cmd("c-t1", T1, START_1930),
                OccupyFixtures.cmd("c-t2", T2, START_1930),
                OccupyFixtures.cmd("c-t3", T3, START_1930)
        ));

        assertThat(report.successes).isEqualTo(2);
        assertThat(report.attempts).isEqualTo(3);
        assertThat(report.codes).contains(ErrorCodes.NO_FREE_BED);
        assertThat(report.codes).allMatch(c -> c == 0 || c == ErrorCodes.NO_FREE_BED || c == ErrorCodes.LOCK_CONFLICT);
        assertThat(store.occupancies).hasSize(20);
        Set<String> unique = store.occupancies.keySet();
        assertThat(unique).hasSize(20);
        assertThat(store.orders).hasSize(2);
        assertThat(store.orders.values().stream().map(o -> o.bedId()).collect(Collectors.toSet())).hasSize(2);
    }

    @Test
    void overlappingSixtyMinuteWindowsAtMostOneBed() throws Exception {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore(1);
        SlotOccupyService service = OccupyFixtures.service(store);
        LockReport report = race(service, List.of(
                OccupyFixtures.cmd("ov-a", T1, START_1930),
                OccupyFixtures.cmd("ov-b", T2, START_2000)
        ));

        assertThat(report.successes).isEqualTo(1);
        assertThat(report.attempts).isEqualTo(2);
        assertThat(report.codes).contains(ErrorCodes.NO_FREE_BED);
        assertThat(store.orders).hasSize(1);
        assertThat(store.occupancies).hasSize(10);
        assertThat(store.occupancies.keySet()).hasSize(10);
    }

    @Test
    void sameTherapistSameStartOnlyOneWins() throws Exception {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService service = OccupyFixtures.service(store);
        LockReport report = race(service, List.of(
                OccupyFixtures.cmd("same-1", T1, START_1930),
                OccupyFixtures.cmd("same-2", T1, START_1930),
                OccupyFixtures.cmd("same-3", T1, START_1930)
        ));

        assertThat(report.successes).isEqualTo(1);
        assertThat(report.codes)
                .allMatch(c -> c == 0 || c == ErrorCodes.LOCK_CONFLICT || c == ErrorCodes.SLOT_UNAVAILABLE);
        assertThat(store.orders).hasSize(1);
        assertThat(store.occupancies.keySet()).hasSize(10);
    }

    static LockReport race(SlotOccupyService service, List<LockNewCommand> commands) throws Exception {
        int n = commands.size();
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (LockNewCommand cmd : commands) {
                tasks.add(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        service.lockNew(cmd);
                        return 0;
                    } catch (ApiException ex) {
                        return ex.getCode();
                    }
                });
            }
            List<Future<Integer>> futures = pool.invokeAll(tasks);
            LockReport report = new LockReport();
            report.attempts = n;
            for (Future<Integer> future : futures) {
                int code = future.get(10, TimeUnit.SECONDS);
                report.codes.add(code);
                if (code == 0) {
                    report.successes++;
                }
            }
            return report;
        } finally {
            pool.shutdownNow();
        }
    }

    static final class LockReport {
        int attempts;
        int successes;
        final List<Integer> codes = new ArrayList<>();
    }
}
