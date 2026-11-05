package com.tilepad.tweakcore;

import java.util.Arrays;

/** Hysteresis and the exact stock economic-charge array layout. No I/O. */
public final class ChargeAwayPolicy {
    public static final double MILE_M = 1609.344;
    public enum Zone { UNKNOWN, HOME, AWAY }
    public static double distance(double lat1, double lon1, double lat2, double lon2) {
        double a = Math.toRadians(lat2 - lat1), b = Math.toRadians(lon2 - lon1);
        double h = Math.sin(a / 2) * Math.sin(a / 2) + Math.cos(Math.toRadians(lat1))
            * Math.cos(Math.toRadians(lat2)) * Math.sin(b / 2) * Math.sin(b / 2);
        return 6371008.8 * 2 * Math.asin(Math.sqrt(Math.max(0, Math.min(1, h))));
    }
    public static Zone zone(Zone before, double metres, double accuracy) {
        if (Double.isNaN(metres) || Double.isInfinite(metres) || metres < 0 || accuracy < 0 || Double.isNaN(accuracy)
            || accuracy > VehicleLocation.MAX_ACCURACY_M) return before;
        if (metres - accuracy > 1.5 * MILE_M) return Zone.AWAY;
        if (metres + accuracy <= MILE_M) return Zone.HOME;
        return before;
    }
    public static void validate(int[] data) {
        if (data == null || data.length != 12) throw new IllegalArgumentException("Unrecognized scheduled-charging reply.");
        if (data[8] != 0 && data[8] != 2 && data[8] != 3) throw new IllegalArgumentException("Unrecognized scheduled-charging mode.");
    }
    public static boolean configured(int[] data) {
        return data[0] >= 0 && data[0] <= 23 && data[1] >= 0 && data[1] <= 5
            && data[2] >= 0 && data[2] <= 23 && data[3] >= 0 && data[3] <= 5;
    }
    public static boolean enabled(int[] data) { validate(data); return data[8] == 2 || data[8] == 3; }
    public static int mode(Zone zone, int[] current, int onlyOffPeak) {
        validate(current);
        if (zone == Zone.UNKNOWN) return current[8];
        if (zone == Zone.AWAY) return 0;
        if (enabled(current)) return current[8]; // Do not rewrite the user's active charging method.
        if (!configured(current)) throw new IllegalArgumentException("Set your off-peak charging times in EV Settings first.");
        if (onlyOffPeak != 0 && onlyOffPeak != 1) throw new IllegalArgumentException("The saved off-peak-only preference could not be read.");
        return onlyOffPeak == 1 ? 3 : 2;
    }
    public static int[] request(int[] current, int desiredMode) {
        validate(current);
        if (desiredMode != 0 && desiredMode != 2 && desiredMode != 3) throw new IllegalArgumentException("Invalid target charging mode");
        int[] request = Arrays.copyOf(current, 9);
        for (int value : request) if (value < 0 || value > 255) throw new IllegalArgumentException("Invalid charging data field");
        request[8] = desiredMode; return request;
    }
    private ChargeAwayPolicy() { }
}
