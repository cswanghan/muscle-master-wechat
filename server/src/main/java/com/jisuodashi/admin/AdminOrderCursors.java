package com.jisuodashi.admin;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Stable `(created_at, id)` DESC cursor. Encodes DATETIME(3) millis so same-second rows are not skipped. */
public final class AdminOrderCursors {

    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
    private static final DateTimeFormatter TS_SEC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private AdminOrderCursors() {
    }

    public static boolean beforeCursor(AdminOrderRow row, Cursor cursor) {
        int cmp = row.createdAt().compareTo(cursor.createdAt());
        if (cmp != 0) {
            return cmp < 0;
        }
        return row.id() < cursor.id();
    }

    public static String encode(AdminOrderRow row) {
        return row.createdAt().format(TS) + "_" + row.id();
    }

    public static String formatCreatedAt(LocalDateTime createdAt) {
        return createdAt == null ? null : createdAt.format(TS);
    }

    public static Cursor parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int split = raw.lastIndexOf('_');
        if (split <= 0 || split == raw.length() - 1) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "cursor 无效");
        }
        String ts = raw.substring(0, split);
        try {
            LocalDateTime createdAt = parseTs(ts);
            long id = Long.parseLong(raw.substring(split + 1));
            return new Cursor(createdAt, id);
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "cursor 无效");
        }
    }

    private static LocalDateTime parseTs(String raw) {
        try {
            return LocalDateTime.parse(raw, TS);
        } catch (DateTimeParseException e) {
            return LocalDateTime.parse(raw, TS_SEC);
        }
    }

    public record Cursor(LocalDateTime createdAt, long id) {
    }
}
