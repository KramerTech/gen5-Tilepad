package com.tilepad.tweakcore;

import java.io.IOException;
import java.util.regex.*;

/** Read the GPS fix already maintained by the head unit; no extra GPS listener or location service. */
public final class VehicleLocation {
    public static final long MAX_AGE_MS = 60000;
    public static final double MAX_ACCURACY_M = 150;
    private static final Pattern GPS = Pattern.compile("(?m)^\\s*gps:\\s*Location\\[gps\\s+([-+0-9.eE]+),([-+0-9.eE]+)\\s+([^\\r\\n]*)");
    public final double latitude, longitude, accuracy;
    public final long elapsedMs;
    public VehicleLocation(double latitude, double longitude, double accuracy, long elapsedMs) {
        this.latitude = latitude; this.longitude = longitude; this.accuracy = accuracy; this.elapsedMs = elapsedMs;
    }
    public boolean usable(long now) {
        return coordinate(latitude, 90) && coordinate(longitude, 180) && !Double.isNaN(accuracy)
            && accuracy >= 0 && accuracy <= MAX_ACCURACY_M && elapsedMs > 0
            && now >= elapsedMs && now - elapsedMs <= MAX_AGE_MS;
    }
    public static boolean coordinate(double n, double limit) { return !Double.isNaN(n) && !Double.isInfinite(n) && Math.abs(n) <= limit; }
    public static VehicleLocation parse(String dump) throws IOException {
        Matcher m = GPS.matcher(dump);
        if (!m.find()) throw new IOException("No GPS fix is available yet.");
        try {
            String rest = m.group(3);
            return new VehicleLocation(Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)),
                Double.parseDouble(field(rest, "acc")), duration(field(rest, "et")));
        } catch (IllegalArgumentException e) { throw new IOException("GPS fix is missing accuracy or elapsed-time information.", e); }
    }
    private static String field(String text, String name) {
        Matcher m = Pattern.compile("(?:^|\\s)" + name + "=([^\\s\\]]+)").matcher(text);
        if (!m.find()) throw new IllegalArgumentException(name);
        return m.group(1);
    }
    /** Location.toString() prints its absolute elapsed timestamp as a signed Android duration. */
    static long duration(String s) {
        if (!s.startsWith("+")) throw new IllegalArgumentException("elapsed timestamp");
        Matcher m = Pattern.compile("(\\d+)(ms|d|h|m|s)").matcher(s.substring(1));
        long result = 0; int end = 0;
        while (m.find()) {
            if (m.start() != end) throw new IllegalArgumentException("duration");
            long n = Long.parseLong(m.group(1)); String unit = m.group(2);
            long scale = unit.equals("d") ? 86400000 : unit.equals("h") ? 3600000 : unit.equals("m") ? 60000 : unit.equals("s") ? 1000 : 1;
            if (n > (Long.MAX_VALUE - result) / scale) throw new IllegalArgumentException("duration overflow");
            result += n * scale; end = m.end();
        }
        if (end != s.length() - 1 || end == 0) throw new IllegalArgumentException("duration");
        return result;
    }
}
