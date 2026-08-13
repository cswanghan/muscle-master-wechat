package com.jisuodashi.admin;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AdminOrderCursorsTest {

    @Test
    void millisCursorKeepsEarlierSameSecondRow() {
        AdminOrderRow later = row(207, LocalDateTime.of(2026, 8, 14, 14, 0, 0, 500_000_000));
        AdminOrderRow earlier = row(208, LocalDateTime.of(2026, 8, 14, 14, 0, 0, 200_000_000));
        String encoded = AdminOrderCursors.encode(later);
        assertThat(encoded).isEqualTo("2026-08-14T14:00:00.500_207");
        AdminOrderCursors.Cursor cursor = AdminOrderCursors.parse(encoded);
        assertThat(AdminOrderCursors.beforeCursor(earlier, cursor)).isTrue();
        assertThat(AdminOrderCursors.beforeCursor(later, cursor)).isFalse();
    }

    @Test
    void secondOnlyCursorWouldSkipEarlierMillisSibling() {
        AdminOrderCursors.Cursor truncated = AdminOrderCursors.parse("2026-08-14T14:00:00_207");
        AdminOrderRow earlierMillis = row(208, LocalDateTime.of(2026, 8, 14, 14, 0, 0, 200_000_000));
        assertThat(AdminOrderCursors.beforeCursor(earlierMillis, truncated)).isFalse();
    }

    private static AdminOrderRow row(long id, LocalDateTime createdAt) {
        return new AdminOrderRow(
                id, "N" + id, 1L, 1L, "COMPLETED", LocalDate.of(2026, 8, 14), createdAt, 1L, false);
    }
}
