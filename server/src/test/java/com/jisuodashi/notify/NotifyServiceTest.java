package com.jisuodashi.notify;

import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 订阅额度与课前提醒。核心是"一次授权只能推一条"这条平台硬规则。 */
class NotifyServiceTest {

    private InMemoryNotifyStore store;
    private RecordingSender sender;
    private NotifyService notify;
    private AppClock clock;

    static final class RecordingSender implements SubscribeMessageSender {
        final List<Long> sent = new ArrayList<>();
        boolean succeed = true;

        @Override
        public boolean send(long customerId, String templateId, String page, Map<String, String> data) {
            if (!succeed) {
                return false;
            }
            sent.add(customerId);
            return true;
        }
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryNotifyStore();
        sender = new RecordingSender();
        clock = new AppClock();
        notify = new NotifyService(store, sender, new SnowflakeIdGenerator(new com.jisuodashi.common.AppProperties(), clock.clock()), clock);
    }

    private BookingOrderRef order(long id, long customerId) {
        // 排在昨天，确保 fireAt 已经过了，扫的时候一定到点。
        return new BookingOrderRef(
                id, "N" + id, id, 1L, 1L, "BOOKED", null, 19800,
                40, 45, 1, null, 100L, clock.today().minusDays(1),
                customerId, 200L, false, false);
    }

    @Test
    void oneGrantSendsExactlyOneMessage() {
        notify.scheduleBeforeClass(order(1L, 900L));
        notify.scheduleBeforeClass(order(2L, 900L));
        notify.grant(900L, "BEFORE_CLASS", null);

        // 两条待推、一条额度 —— 平台规则决定了只能推一条。
        assertThat(notify.fireDue(10)).isEqualTo(1);
        assertThat(sender.sent).hasSize(1);

        // 再扫一轮也不会多推：额度用完就没了。
        assertThat(notify.fireDue(10)).isZero();
    }

    @Test
    void missingGrantIsSkippedNotFailed() {
        notify.scheduleBeforeClass(order(3L, 901L));
        // 用户没授权是正常情况，不该反复重试也不该报警。
        assertThat(notify.fireDue(10)).isZero();
        assertThat(sender.sent).isEmpty();

        // 已经 SKIPPED 的不会再被扫出来。
        notify.grant(901L, "BEFORE_CLASS", null);
        assertThat(notify.fireDue(10)).isZero();
    }

    @Test
    void sendFailureKeepsTheGrantForTheNextSweep() {
        notify.scheduleBeforeClass(order(4L, 902L));
        notify.grant(902L, "BEFORE_CLASS", null);

        sender.succeed = false;
        assertThat(notify.fireDue(10)).isZero();

        // 可重试的失败：额度不销，下一轮还能用。
        sender.succeed = true;
        assertThat(notify.fireDue(10)).isEqualTo(1);
    }

    @Test
    void schedulingTheSameOrderTwiceKeepsOneReminder() {
        BookingOrderRef o = order(5L, 903L);
        notify.scheduleBeforeClass(o);
        notify.scheduleBeforeClass(o);
        notify.grant(903L, "BEFORE_CLASS", null);
        notify.grant(903L, "BEFORE_CLASS", null);

        // 同一单只提醒一次，哪怕额度有两条。
        assertThat(notify.fireDue(10)).isEqualTo(1);
    }

    @Test
    void futureLessonsAreNotFiredYet() {
        BookingOrderRef future = new BookingOrderRef(
                6L, "N6", 6L, 1L, 1L, "BOOKED", null, 19800,
                40, 45, 1, null, 100L, clock.today().plusDays(3),
                904L, 200L, false, false);
        notify.scheduleBeforeClass(future);
        notify.grant(904L, "BEFORE_CLASS", null);
        // 三天后的课现在不该推。
        assertThat(notify.fireDue(10)).isZero();
    }
}
