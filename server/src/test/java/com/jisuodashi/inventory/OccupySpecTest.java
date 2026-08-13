package com.jisuodashi.inventory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OccupySpecTest {

    @Test
    void nIsCeilDurationPlusBufferOver15AndBufferSlotsIs1() {
        OccupySpec p60 = OccupySpec.of(60, 15);
        assertThat(p60.slotCount()).isEqualTo(5);
        assertThat(p60.bufferSlots()).isEqualTo(1);
        assertThat(p60.slotNos(78)).containsExactly(78, 79, 80, 81, 82);
        assertThat(p60.endSlotNo(78)).isEqualTo(83);
        assertThat(p60.isBuffer(78, 81)).isFalse();
        assertThat(p60.isBuffer(78, 82)).isTrue();
        assertThat(p60.destStatus(78, 81)).isEqualTo(SlotStatus.LOCKED);
        assertThat(p60.destStatus(78, 82)).isEqualTo(SlotStatus.LOCKED);

        assertThat(OccupySpec.of(45, 15).slotCount()).isEqualTo(4);
        assertThat(OccupySpec.of(90, 15).slotCount()).isEqualTo(7);
        assertThat(OccupySpec.of(60, 10).slotCount()).isEqualTo(5);
    }
}
