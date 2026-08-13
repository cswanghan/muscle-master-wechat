package com.jisuodashi.catalog;

public final class Geo {

    private static final double EARTH_M = 6_371_000d;

    private Geo() {
    }

    public static int distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return (int) Math.round(2 * EARTH_M * Math.asin(Math.min(1.0, Math.sqrt(a))));
    }
}
