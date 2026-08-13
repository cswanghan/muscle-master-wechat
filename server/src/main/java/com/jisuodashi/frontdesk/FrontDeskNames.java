package com.jisuodashi.frontdesk;

/** Demo room/bed labels (V3) plus phone mask for the desk screen. */
final class FrontDeskNames {

    static final long ROOM_1 = 3_100_000_000_000_000_101L;
    static final long BED_1 = 3_100_000_000_000_000_201L;
    static final long BED_2 = 3_100_000_000_000_000_202L;

    private FrontDeskNames() {
    }

    static String roomName(long roomId) {
        if (roomId == ROOM_1) {
            return "一号房";
        }
        return "房间";
    }

    static String bedName(long bedId) {
        if (bedId == BED_1) {
            return "1号床";
        }
        if (bedId == BED_2) {
            return "2号床";
        }
        return "床位";
    }

    static String maskPhone(String raw) {
        String digits = digits(raw);
        if (digits.length() < 7) {
            return digits.isEmpty() ? "****" : digits;
        }
        return digits.substring(0, 3) + "****" + digits.substring(digits.length() - 4);
    }

    static String digits(String raw) {
        if (raw == null) {
            return "";
        }
        String d = raw.replaceAll("\\D", "");
        if (d.startsWith("86") && d.length() == 13) {
            return d.substring(2);
        }
        return d;
    }
}
