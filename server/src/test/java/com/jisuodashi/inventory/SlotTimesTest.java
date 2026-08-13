package com.jisuodashi.inventory;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlotTimesTest {

    @Test
    void slotNoIsHourTimes4PlusMinuteOver15() {
        assertThat(SlotTimes.toSlotNo(LocalTime.of(0, 0))).isZero();
        assertThat(SlotTimes.toSlotNo(LocalTime.of(10, 0))).isEqualTo(40);
        assertThat(SlotTimes.toSlotNo(LocalTime.of(14, 0))).isEqualTo(56);
        assertThat(SlotTimes.toSlotNo(LocalTime.of(16, 0))).isEqualTo(64);
        assertThat(SlotTimes.toSlotNo(LocalTime.of(19, 30))).isEqualTo(78);
        assertThat(SlotTimes.toSlotNo(LocalTime.of(22, 0))).isEqualTo(88);
    }

    @Test
    void toTimeRoundTrip() {
        for (int slot = 0; slot < SlotTimes.SLOTS_PER_DAY; slot++) {
            assertThat(SlotTimes.toSlotNo(SlotTimes.toTime(slot))).isEqualTo(slot);
        }
    }

    @Test
    void rangeIsHalfOpen() {
        assertThat(SlotTimes.range(LocalTime.of(10, 0), LocalTime.of(22, 0)))
                .hasSize(48)
                .startsWith(40)
                .endsWith(87)
                .doesNotContain(88);
        assertThat(SlotTimes.range(LocalTime.of(14, 0), LocalTime.of(16, 0)))
                .containsExactly(56, 57, 58, 59, 60, 61, 62, 63);
    }

    @Test
    void rejectsUnalignedMinutes() {
        assertThatThrownBy(() -> SlotTimes.toSlotNo(LocalTime.of(10, 7)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zoneIsShanghai() {
        assertThat(SlotTimes.ZONE.getId()).isEqualTo("Asia/Shanghai");
    }
}
