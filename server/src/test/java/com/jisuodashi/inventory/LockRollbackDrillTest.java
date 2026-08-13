package com.jisuodashi.inventory;

import com.jisuodashi.common.AppProperties;
import com.jisuodashi.inventory.InMemorySlotOccupyStore.MutableSlot;
import com.jisuodashi.job.ForceReleaseJob;
import com.jisuodashi.job.JobRunner;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static com.jisuodashi.inventory.OccupyFixtures.BED1;
import static com.jisuodashi.inventory.OccupyFixtures.START_1930;
import static com.jisuodashi.inventory.OccupyFixtures.T1;
import static com.jisuodashi.inventory.OccupyFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design §坏锁回滚 drill: plant stuck LOCKED, ForceReleaseJob / forceFreeByHold,
 * assert FREE + occupancy gone. 5 min scan stays; Flyway is never rolled back.
 */
class LockRollbackDrillTest {

    @Test
    void bookingLockEnabledDefaultsTrueAndCanBeFlipped() {
        AppProperties props = new AppProperties();
        assertThat(props.getBooking().getLock().isEnabled()).isTrue();
        props.getBooking().getLock().setEnabled(false);
        assertThat(props.getBooking().getLock().isEnabled()).isFalse();
    }

    @Test
    void forceReleaseJobFreesPendingPayLeftoverLockedSlots() {
        InMemorySlotOccupyStore store = OccupyFixtures.demoStore();
        SlotOccupyService occupy = OccupyFixtures.service(store);
        LockNewResult locked = occupy.lockNew(OccupyFixtures.cmd("drill-pending", T1, START_1930));
        assertThat(store.occupancies).hasSize(10);
        assertThat(store.therapistSlot(T1, TODAY, START_1930).status).isEqualTo(SlotStatus.LOCKED);

        ReleaseResult forced = new ForceReleaseJob(occupy).run(locked.holdId());
        assertThat(forced.freed()).isTrue();
        assertThat(forced.outcome()).isEqualTo(ReleaseResult.ORPHAN_FREED);
        assertThat(store.occupancies).isEmpty();
        assertSlotsFree(store, locked.holdId());
    }

    @Test
    void forceFreeByHoldReleasesStuckOrphanAndLeavesPaidBookedAlone() {
        InMemorySlotOccupyStore orphanStore = OccupyFixtures.demoStore();
        SlotOccupyService orphanSvc = OccupyFixtures.service(orphanStore);
        long orphanHold = 7_180_000_000_000_000_001L;
        plantBedOnly(orphanStore, BED1, orphanHold, TODAY.atTime(18, 40));
        assertThat(orphanStore.occupancies).hasSize(5);

        ReleaseResult freed = orphanSvc.forceFreeByHold(orphanHold);
        assertThat(freed.outcome()).isEqualTo(ReleaseResult.ORPHAN_FREED);
        assertThat(orphanStore.occupancies).isEmpty();
        for (int slot = 78; slot <= 82; slot++) {
            MutableSlot bed = orphanStore.bedSlot(BED1, TODAY, slot);
            assertThat(bed.status).isEqualTo(SlotStatus.FREE);
            assertThat(bed.holdId).isNull();
        }

        InMemorySlotOccupyStore paidStore = OccupyFixtures.demoStore();
        SlotOccupyService paidSvc = OccupyFixtures.service(paidStore);
        LockNewResult paid = paidSvc.lockNew(OccupyFixtures.cmd("drill-paid", T1, START_1930));
        paidSvc.confirmPaidSlots(paid.orderId());
        paidStore.setOrderStatus(paid.orderId(), "BOOKED");
        ReleaseResult skipped = new ForceReleaseJob(paidSvc).run(paid.holdId());
        assertThat(skipped.outcome()).isEqualTo(ReleaseResult.IDEMPOTENT);
        assertThat(paidStore.occupancies).hasSize(10);
        assertThat(paidStore.therapistSlot(T1, TODAY, START_1930).status).isEqualTo(SlotStatus.BOOKED);
    }

    @Test
    void fiveMinuteScanMustRemainAndFlywayIsAddOnly() throws Exception {
        Method scan = JobRunner.class.getDeclaredMethod("scanExpiredLocksEvery5Min");
        Scheduled scheduled = scan.getAnnotation(Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo("0 */5 * * * *");
        assertThat(scheduled.zone()).isEqualTo("Asia/Shanghai");

        Path migrations = resolveRepoRoot().resolve("server/src/main/resources/db/migration");
        assertThat(Files.isDirectory(migrations)).isTrue();
        try (Stream<Path> files = Files.list(migrations)) {
            List<String> names = files.map(p -> p.getFileName().toString()).sorted().toList();
            assertThat(names).isNotEmpty();
            assertThat(names).allMatch(n -> n.matches("V\\d+__.+\\.sql"));
            assertThat(names).noneMatch(n -> n.toLowerCase().contains("undo") || n.startsWith("U"));
        }

        Path runbook = resolveRepoRoot().resolve("docs/runbooks/lock-rollback.md");
        assertThat(runbook).exists();
        String text = Files.readString(runbook);
        assertThat(text).contains("booking.lock.enabled=false");
        assertThat(text).contains("ForceReleaseJob");
        assertThat(text).contains("force-release");
        assertThat(text).contains("不要回滚 Flyway");
        assertThat(text).contains("5 min");
    }

    private static void plantBedOnly(InMemorySlotOccupyStore store, long bedId, long holdId, LocalDateTime expire) {
        store.beginWork();
        for (int slot = 78; slot <= 82; slot++) {
            MutableSlot row = store.bedSlot(bedId, TODAY, slot);
            row.status = SlotStatus.LOCKED;
            row.holdId = holdId;
            row.lockExpireAt = expire;
            store.insertOccupancy(new SlotOccupyStore.OccupancyInsert(
                    holdId + slot, ResourceType.BED, bedId, TODAY, slot, holdId, holdId, expire));
        }
        store.commitWork();
    }

    private static void assertSlotsFree(InMemorySlotOccupyStore store, long holdId) {
        for (int slot = 78; slot <= 82; slot++) {
            MutableSlot t = store.therapistSlot(T1, TODAY, slot);
            assertThat(t.status).isEqualTo(SlotStatus.FREE);
            assertThat(t.holdId).isNull();
            MutableSlot b = store.bedSlot(BED1, TODAY, slot);
            assertThat(b.status).isEqualTo(SlotStatus.FREE);
            assertThat(b.holdId).isNull();
        }
        assertThat(store.occupancies.values()).noneMatch(o -> o.holdId() == holdId);
    }

    private static Path resolveRepoRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (Files.isRegularFile(cwd.resolve("server/pom.xml"))) {
            return cwd;
        }
        if (Files.isRegularFile(cwd.resolve("pom.xml")) && "server".equals(cwd.getFileName().toString())) {
            return cwd.getParent();
        }
        return cwd;
    }
}
