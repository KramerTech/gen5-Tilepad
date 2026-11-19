package com.tilepad.tweakcore;

import java.util.regex.*;

public final class HeaterPolicy {
    /**
     * IUSMService getters for the heaters the car is equipped with (FrontSeatHeat_DrvOption,
     * FrontSeatHeat_AstOption, SWRC_SteeringwheelHeatingOpt); they read 0 when not fitted, and the
     * front-seat options read 6 when both heating and ventilation are fitted.
     */
    public static final int SEAT_HEATED_AND_COOLED = 6;
    static int fittedTransaction(Firmware.Usm usm, String key) {
        switch (key) {
            case "driver": return usm.driverFitted;
            case "passenger": return usm.passengerFitted;
            case "wheel": return usm.wheelFitted;
            default: throw new IllegalArgumentException(key);
        }
    }
    public static boolean isFitted(String key, int option) {
        if (!"driver".equals(key) && !"passenger".equals(key) && !"wheel".equals(key)) throw new IllegalArgumentException(key);
        return option != 0;
    }
    public static boolean isCoolingFitted(String key, int option) {
        if (!"driver".equals(key) && !"passenger".equals(key) && !"wheel".equals(key)) throw new IllegalArgumentException(key);
        return !"wheel".equals(key) && option == SEAT_HEATED_AND_COOLED;
    }
    /** Only a fresh, valid raw signal authorizes its decoded temperature. No rounding at threshold. */
    public static Double outsideC(String dump) {
        Double c = reading(dump, "C", -40, 60);
        if (c != null) return c;
        Double f = reading(dump, "F", -40, 140);
        return f == null ? null : (f - 32) * 5 / 9;
    }
    private static Double reading(String dump, String unit, double min, double max) {
        Matcher raw = Pattern.compile("(?m)^\\s*AmbientTemp" + unit + "\\s*:\\s*0x([0-9a-fA-F]+)\\s*$").matcher(dump);
        Matcher value = Pattern.compile("(?m)^\\s*mAmbientTemp" + unit + ":\\s*(-?\\d+(?:\\.\\d+)?)\\s*$").matcher(dump);
        if (!raw.find() || !value.find()) return null;
        long signal = Long.parseLong(raw.group(1), 16);
        if (signal == 0 || signal == 255 || signal == 65535) return null;
        double n = Double.parseDouble(value.group(1));
        return n >= min && n <= max ? n : null;
    }
    /** What the dump showed for each outdoor-temperature channel, e.g. "°F signal 0x60, shown as 56.0", for a status that gives up. */
    public static String reported(String dump) {
        StringBuilder out = new StringBuilder();
        for (String unit : new String[]{"F", "C"}) {
            Matcher raw = Pattern.compile("(?m)^\\s*AmbientTemp" + unit + "\\s*:\\s*(0x[0-9a-fA-F]+)\\s*$").matcher(dump);
            Matcher value = Pattern.compile("(?m)^\\s*mAmbientTemp" + unit + ":\\s*(\\S+)\\s*$").matcher(dump);
            if (out.length() > 0) out.append(" and ");
            out.append("°").append(unit).append(" signal ").append(raw.find() ? raw.group(1) : "missing")
                .append(", shown as ").append(value.find() ? value.group(1) : "nothing");
        }
        return out.toString();
    }
    /** Verified USM request enum: cool Low=3..High=5, heat Low=6..High=8; 1..3 are NOT request values. */
    public static int seatRequest(int level) { return seatRequest(false, level); }
    public static int coolRequest(int level) { return seatRequest(true, level); }
    public static int seatRequest(boolean cool, int level) {
        if (level < 1 || level > 3) throw new IllegalArgumentException("Seat level must be 1, 2 or 3");
        return (cool ? 2 : 5) + level;
    }
    public static boolean cold(double c, int thresholdTenthsC) { return c * 10 <= thresholdTenthsC; }
    /** Warm-weather seat ventilation level (1 Low, 2 Medium, 3 High), or 0 below all cooling thresholds. */
    public static int coolingLevel(double c, int lowTenthsC, int medTenthsC, int highTenthsC) {
        double tenths = c * 10;
        if (tenths >= highTenthsC) return 3;
        if (tenths >= medTenthsC) return 2;
        if (tenths >= lowTenthsC) return 1;
        return 0;
    }
    static final class PassengerWait {
        final String boot, outcome;
        final int coolLevel;
        PassengerWait(String boot, int coolLevel, String outcome) {
            this.boot = boot; this.coolLevel = coolLevel; this.outcome = outcome;
        }
    }
    static String passengerWaitRecord(String boot, int coolLevel, String outcome) {
        return boot + "\t" + coolLevel + "\t" + outcome.replace('\t', ' ');
    }
    static PassengerWait parsePassengerWait(String content) {
        if (content.indexOf('\t') >= 0) {
            String[] parts = content.split("\t", 3);
            int coolLevel = Integer.parseInt(parts[1]);
            if (coolLevel < 0 || coolLevel > 3) throw new IllegalArgumentException("Invalid cooling level: " + coolLevel);
            return new PassengerWait(parts[0], coolLevel, parts.length > 2 ? parts[2] : "");
        }
        String[] parts = content.split(" ", 2);
        return new PassengerWait(parts[0], 0, parts.length > 1 ? parts[1] : "");
    }
    private HeaterPolicy() { }
}
