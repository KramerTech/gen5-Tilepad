package com.tilepad.tweakcore;

import java.util.regex.*;

public final class HeaterPolicy {
    /**
     * IUSMService getters for the heaters the car is equipped with (FrontSeatHeat_DrvOption,
     * FrontSeatHeat_AstOption, SWRC_SteeringwheelHeatingOpt); they read 0 when it isn't fitted.
     */
    static int fittedTransaction(Firmware.Usm usm, String key) {
        switch (key) {
            case "driver": return usm.driverFitted;
            case "passenger": return usm.passengerFitted;
            case "wheel": return usm.wheelFitted;
            default: throw new IllegalArgumentException(key);
        }
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
    /** Verified USM request enum: heat Low=6, Medium=7, High=8; 1..3 are NOT request values. */
    public static int seatRequest(int level) {
        if (level < 1 || level > 3) throw new IllegalArgumentException("Seat level must be 1, 2 or 3");
        return 5 + level;
    }
    public static boolean cold(double c, int thresholdTenthsC) { return c * 10 < thresholdTenthsC; }
    private HeaterPolicy() { }
}
