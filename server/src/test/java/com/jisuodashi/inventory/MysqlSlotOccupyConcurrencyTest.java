package com.jisuodashi.inventory;

import com.jisuodashi.catalog.DemoCatalogIds;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.db.MysqlBackedTest;
import com.jisuodashi.inventory.SlotOccupyConcurrencyTest.LockReport;
import com.jisuodashi.inventory.persist.MybatisSlotOccupyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SlotOccupyConcurrencyTest} on real InnoDB.
 *
 * <p>The in-memory twin proves the algorithm; it cannot prove the mechanism the algorithm leans on.
 * Ordered {@code SELECT ... FOR UPDATE}, the {@code uk_occ} unique key and deadlock retry only
 * exist because MySQL behaves the way it does, and a {@code ConcurrentHashMap} will happily agree
 * with a query that InnoDB would reject. Same races, same expectations, real database.
 *
 * <p>No {@code @Transactional}: these tests need real commits racing each other, so cleanup is
 * explicit in {@link #resetCalendar()}.
 */
@MysqlBackedTest
class MysqlSlotOccupyConcurrencyTest {

    private static final LocalDate DAY = LocalDate.parse(MysqlBackedTest.FIXED_AT.substring(0, 10));
    private static final long STORE = DemoCatalogIds.STORE;
    private static final long P60 = DemoCatalogIds.PROJECT_P60;
    private static final long CUSTOMER = 8_100_000_000_000_000_001L;

    /** 19:30 and 20:00. P60 spans five slots (60 + 15 buffer), so these two windows overlap. */
    private static final int START_1930 = 78;
    private static final int START_2000 = 80;

    /** Five therapist rows + five bed rows per booking. */
    private static final int OCCUPANCY_PER_ORDER = 10;

    /** Written per test, so anything left over is a leak rather than a fixture. */
    private static final List<String> VOLATILE_TABLES = List.of(
            "slot_occupancy", "order_item", "order_change_log", "payment",
            "delayed_job", "idempotency_record", "human_task", "booking_order");

    @Autowired
    private SlotOccupyService service;

    @Autowired
    private SlotGenerateService generate;

    @Autowired
    private SlotOccupyStore store;

    @Autowired
    private AppClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetCalendar() {
        VOLATILE_TABLES.forEach(table -> jdbc.update("DELETE FROM " + table));
        Integer slots = jdbc.queryForObject("SELECT COUNT(*) FROM therapist_slot", Integer.class);
        if (slots == null || slots == 0) {
            // Once per JVM, and through the real generator so MybatisSlotGenerateStore is covered too.
            generate.generate(clock.today());
        } else {
            jdbc.update("UPDATE therapist_slot SET status = 'FREE', order_id = NULL, hold_id = NULL,"
                    + " lock_expire_at = NULL WHERE status <> 'REST'");
            jdbc.update("UPDATE bed_slot SET status = 'FREE', order_id = NULL, hold_id = NULL,"
                    + " lock_expire_at = NULL WHERE status <> 'REST'");
        }
    }

    /**
     * Without this the whole class could pass while silently exercising the in-memory stores again
     * — the exact gap it was written to close.
     */
    @Test
    void runsAgainstTheShippedMysqlStores() {
        assertThat(store).isInstanceOf(MybatisSlotOccupyStore.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class))
                .isGreaterThanOrEqualTo(5);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM bed WHERE store_id = ? AND deleted_at IS NULL", Integer.class, STORE))
                .isEqualTo(2);
    }

    /** The demo store has two beds; a third simultaneous booking has nowhere to go. */
    @Test
    void threeTherapistsTwoBedsExactlyTwoSucceed() throws Exception {
        LockReport report = SlotOccupyConcurrencyTest.race(service, List.of(
                cmd("mysql-t1", DemoCatalogIds.THERAPIST_LIN, START_1930),
                cmd("mysql-t2", DemoCatalogIds.THERAPIST_CHEN, START_1930),
                cmd("mysql-t3", DemoCatalogIds.THERAPIST_ZHOU, START_1930)));

        assertThat(report.attempts).isEqualTo(3);
        assertThat(report.successes).isEqualTo(2);
        assertThat(report.codes).contains(ErrorCodes.NO_FREE_BED);
        assertThat(report.codes)
                .allMatch(c -> c == 0 || c == ErrorCodes.NO_FREE_BED || c == ErrorCodes.LOCK_CONFLICT);
        assertThat(orderCount()).isEqualTo(2);
        assertThat(occupancyCount()).isEqualTo(2 * OCCUPANCY_PER_ORDER);
        assertNoSlotSoldTwice();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(DISTINCT bed_id) FROM booking_order", Integer.class)).isEqualTo(2);
    }

    /**
     * With one bed already taken, two overlapping windows are left competing for the other. The
     * loser must be turned away by the calendar, not by squeezing onto the same bed.
     */
    @Test
    void overlappingWindowsCannotShareTheLastBed() throws Exception {
        service.lockNew(cmd("mysql-seed", DemoCatalogIds.THERAPIST_ZHOU, START_1930));

        LockReport report = SlotOccupyConcurrencyTest.race(service, List.of(
                cmd("mysql-ov-a", DemoCatalogIds.THERAPIST_LIN, START_1930),
                cmd("mysql-ov-b", DemoCatalogIds.THERAPIST_CHEN, START_2000)));

        assertThat(report.successes).isEqualTo(1);
        assertThat(report.codes).contains(ErrorCodes.NO_FREE_BED);
        assertThat(orderCount()).isEqualTo(2);
        assertThat(occupancyCount()).isEqualTo(2 * OCCUPANCY_PER_ORDER);
        assertNoSlotSoldTwice();
    }

    /** One therapist cannot be in two places at 19:30, however many requests arrive together. */
    @Test
    void sameTherapistSameStartOnlyOneWins() throws Exception {
        LockReport report = SlotOccupyConcurrencyTest.race(service, List.of(
                cmd("mysql-same-1", DemoCatalogIds.THERAPIST_LIN, START_1930),
                cmd("mysql-same-2", DemoCatalogIds.THERAPIST_LIN, START_1930),
                cmd("mysql-same-3", DemoCatalogIds.THERAPIST_LIN, START_1930)));

        assertThat(report.successes).isEqualTo(1);
        assertThat(report.codes).allMatch(
                c -> c == 0 || c == ErrorCodes.LOCK_CONFLICT || c == ErrorCodes.SLOT_UNAVAILABLE);
        assertThat(orderCount()).isEqualTo(1);
        assertThat(occupancyCount()).isEqualTo(OCCUPANCY_PER_ORDER);
        assertNoSlotSoldTwice();
    }

    /**
     * The last line of defence, asserted directly: every guard above it can be reasoned wrong, but
     * {@code uk_occ} makes a double sale unrepresentable. Losing this key would leave the retry and
     * {@code FOR UPDATE} code above it defending nothing.
     */
    @Test
    void occupancyUniqueKeyRefusesTheSameSlotTwice() {
        service.lockNew(cmd("mysql-uk", DemoCatalogIds.THERAPIST_LIN, START_1930));

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO slot_occupancy"
                        + " (id, resource_type, resource_id, slot_date, slot_no, order_id, hold_id, created_at)"
                        + " SELECT id + 1, resource_type, resource_id, slot_date, slot_no, order_id, hold_id,"
                        + " created_at FROM slot_occupancy WHERE resource_type = 'THERAPIST' LIMIT 1"))
                .isInstanceOf(DuplicateKeyException.class);

        assertThat(occupancyCount()).isEqualTo(OCCUPANCY_PER_ORDER);
    }

    private LockNewCommand cmd(String requestId, long therapistId, int startSlotNo) {
        return new LockNewCommand(
                requestId, CUSTOMER, STORE, therapistId, P60, DAY, startSlotNo,
                LockNewCommand.SOURCE_MINI_C);
    }

    private int orderCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM booking_order", Integer.class);
    }

    private int occupancyCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM slot_occupancy", Integer.class);
    }

    /** {@code uk_occ} should make this impossible; assert it rather than trust it. */
    private void assertNoSlotSoldTwice() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(DISTINCT resource_type, resource_id, slot_date, slot_no)"
                        + " FROM slot_occupancy", Integer.class))
                .isEqualTo(occupancyCount());
    }
}
