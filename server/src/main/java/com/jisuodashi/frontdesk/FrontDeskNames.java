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
        return com.jisuodashi.common.PhoneCrypto.mask(raw);
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
