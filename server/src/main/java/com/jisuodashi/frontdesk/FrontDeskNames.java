package com.jisuodashi.frontdesk;

/** Phone mask for the desk screen. Room/bed labels come from catalog. */
final class FrontDeskNames {

    private FrontDeskNames() {
    }

    static String roomName(String catalogName) {
        return catalogName == null || catalogName.isBlank() ? "房间" : catalogName;
    }

    static String bedName(String catalogName) {
        return catalogName == null || catalogName.isBlank() ? "床位" : catalogName;
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
