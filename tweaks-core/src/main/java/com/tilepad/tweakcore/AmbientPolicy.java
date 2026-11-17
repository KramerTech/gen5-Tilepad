package com.tilepad.tweakcore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Pure clean-room domain logic for Ambient Lighting: Hyundai/Kia 64-color MICOM wheel
 * quantization, SML protocol encoding/decoding, per-mode targets (Manual, Day and Night with
 * sunrise/sunset fade and car-theme fallback, Climate, Weather with 1-hour telematics cache or
 * editable outside-temp thresholds, and Music with smooth Park/Drive choices), rate limits,
 * 3-second self-write detection, and atomic owner state backup/restoration. No Android or I/O.
 */
public final class AmbientPolicy {
    public enum Brand { HYUNDAI, KIA }

    public enum Mode {
        OFF("off", "Off"),
        MANUAL("manual", "Manual"),
        DAY_NIGHT("daynight", "Day and Night"),
        CLIMATE("climate", "Climate"),
        WEATHER("weather", "Weather"),
        MUSIC("music", "Music");

        public final String id, label;
        Mode(String id, String label) { this.id = id; this.label = label; }
        public static Mode of(String id) {
            for (Mode m : values()) if (m.id.equals(id)) return m;
            return DAY_NIGHT;
        }
    }

    public enum Equipment {
        AUTO("auto", "Auto"),
        FITTED("fitted", "Fitted"),
        NOT_FITTED("not_fitted", "Not fitted");

        public final String id, label;
        Equipment(String id, String label) { this.id = id; this.label = label; }
        public static Equipment of(String id) {
            for (Equipment e : values()) if (e.id.equals(id)) return e;
            return AUTO;
        }
    }

    public enum MusicStyle {
        GENTLE_PULSE("pulse", "Gentle Pulse"),
        SLOW_SPECTRUM("spectrum", "Slow Spectrum");

        public final String id, label;
        MusicStyle(String id, String label) { this.id = id; this.label = label; }
        public static MusicStyle of(String id) {
            for (MusicStyle s : values()) if (s.id.equals(id)) return s;
            return GENTLE_PULSE;
        }
    }

    public enum MusicDriveBehavior {
        PAUSE("pause", "Pause"),
        SLOW_CYCLE("cycle", "Slow Color Cycle");

        public final String id, label;
        MusicDriveBehavior(String id, String label) { this.id = id; this.label = label; }
        public static MusicDriveBehavior of(String id) {
            for (MusicDriveBehavior b : values()) if (b.id.equals(id)) return b;
            return PAUSE;
        }
    }

    public enum ClimateState {
        OFF("off", "Climate Off"),
        HEATING("heating", "Heating"),
        COOLING("cooling", "Cooling"),
        DEFROST("defrost", "Defrost");

        public final String id, label;
        ClimateState(String id, String label) { this.id = id; this.label = label; }
    }

    public enum WeatherCondition {
        UNKNOWN, CLEAR, CLOUDS, RAIN, STORM, SNOW, FOG
    }

    /** Mode constants for ISystemService.setSMLConfiguration(mode, data). */
    public static final int SML_MODE_BRIGHTNESS = 5;
    public static final int SML_MODE_DIM_DARK = 8;
    public static final int SML_MODE_DRIVE_LINK = 9;

    public static final int SML_OFF = 1;
    public static final int SML_ON = 2;

    /** Minimum transport interval between SML writes (<= 10 Hz). */
    public static final long MIN_COMMAND_GAP_MS = 100L;
    /** Echo window for recognizing our own SML writes vs manual owner menu edits. */
    public static final long SELF_WRITE_WINDOW_MS = 3000L;
    /** Minimum dwell before changing Day/Night preset and Weather tint. */
    public static final long DAY_NIGHT_DEBOUNCE_MS = 5000L;
    public static final long WEATHER_COOLDOWN_MS = 30000L;
    public static final long WEATHER_CACHE_FRESH_MS = 60 * 60 * 1000L;
    /** Half-width of sunrise/sunset color transition window, in minutes. */
    public static final int SUN_FADE_HALF_WINDOW_MIN = 30;
    /** Default Cold/Hot thresholds in tenths C (5.0 °C = 41 °F, 27.0 °C = 81 °F). */
    public static final int DEFAULT_COLD_TENTHS_C = 50;
    public static final int DEFAULT_HOT_TENTHS_C = 270;
    /** Music constraints: no strobing, >= 1 s color dwell, 1-step brightness slew, 30% band-switch hysteresis. */
    public static final long MUSIC_COLOR_DWELL_MS = 1000L;
    public static final double MUSIC_HYSTERESIS = 0.30;
    public static final int MUSIC_MAX_LEVEL_SLEW = 1;
    public static final int AUDIO_MODE_RINGTONE = 1;
    public static final int AUDIO_MODE_IN_CALL = 2;
    public static final int AUDIO_MODE_IN_COMMUNICATION = 3;
    /** Slow color cycle advances at most ~1 wheel step per second while driving cleanly. */
    public static final long MUSIC_CYCLE_STEP_MS = 1000L;

    private static final int[] HYUNDAI_R = {
        176, 195, 205, 232, 255, 255, 255, 255, 255, 255, 255, 255, 254, 255, 254, 254,
        255, 255, 255, 255, 255, 255, 255, 254, 255, 255, 255, 255, 255, 255, 255, 255,
        255, 254, 255, 254, 247, 230, 210, 175, 119, 58, 10, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 25, 55, 81, 100, 109, 122, 125, 128, 130, 143, 156, 167
    };
    private static final int[] HYUNDAI_G = {
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        96, 107, 73, 88, 113, 125, 3, 34, 48, 60, 65, 137, 148, 160, 172, 177,
        191, 207, 216, 237, 255, 254, 255, 254, 254, 255, 255, 217, 180, 161, 140, 125,
        115, 99, 85, 62, 0, 0, 0, 0, 42, 52, 25, 0, 0, 0, 0, 0
    };
    private static final int[] HYUNDAI_B = {
        255, 254, 254, 254, 241, 195, 158, 137, 117, 111, 106, 101, 95, 72, 58, 30,
        106, 116, 73, 88, 113, 115, 10, 0, 23, 42, 49, 107, 119, 111, 113, 108,
        123, 138, 147, 165, 182, 186, 188, 191, 196, 198, 235, 245, 255, 255, 255, 255,
        254, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255
    };

    private static final int[] KIA_R = {
        16, 16, 16, 33, 47, 64, 82, 100, 120, 136, 155, 169, 183, 193, 204, 213,
        221, 228, 235, 239, 246, 250, 254, 255, 255, 255, 255, 255, 255, 255, 255, 255,
        255, 255, 254, 255, 246, 233, 220, 202, 166, 168, 149, 132, 117, 104, 93, 81,
        71, 62, 53, 44, 37, 30, 25, 20, 16, 16, 16, 16, 16, 16, 16, 16
    };
    private static final int[] KIA_G = {
        67, 46, 32, 28, 28, 28, 28, 28, 28, 29, 33, 35, 35, 33, 31, 27,
        23, 19, 14, 10, 6, 4, 1, 50, 68, 80, 105, 120, 143, 166, 195, 205,
        218, 232, 243, 255, 255, 255, 255, 252, 226, 243, 235, 229, 223, 221, 222, 225,
        231, 238, 245, 250, 250, 250, 250, 250, 244, 230, 210, 187, 163, 138, 111, 89
    };
    private static final int[] KIA_B = {
        244, 242, 239, 239, 239, 239, 239, 239, 239, 239, 239, 239, 234, 224, 210, 192,
        174, 153, 153, 116, 96, 81, 69, 29, 35, 39, 48, 53, 62, 70, 88, 118,
        158, 200, 230, 255, 246, 221, 188, 147, 91, 98, 98, 98, 98, 98, 105, 118,
        133, 152, 169, 189, 207, 225, 237, 249, 252, 252, 252, 252, 252, 252, 252, 248
    };

    private AmbientPolicy() { }

    public static Brand brandForProduct(String productOrDevice) {
        if (productOrDevice == null) return Brand.HYUNDAI;
        String s = productOrDevice.toLowerCase(Locale.US);
        if (s.contains("kia") || s.contains("cvev") || s.contains("cv_") || s.contains("_cv")
            || s.contains("deev") || s.contains("nq5") || s.contains("sg2")) return Brand.KIA;
        return Brand.HYUNDAI;
    }

    public static int paletteSize() { return 64; }

    public static int colorAt(Brand brand, int index) {
        int i = Math.max(0, Math.min(63, index));
        int[] r = brand == Brand.KIA ? KIA_R : HYUNDAI_R;
        int[] g = brand == Brand.KIA ? KIA_G : HYUNDAI_G;
        int[] b = brand == Brand.KIA ? KIA_B : HYUNDAI_B;
        return ((r[i] & 0xff) << 16) | ((g[i] & 0xff) << 8) | (b[i] & 0xff);
    }

    public static int[] palette(Brand brand) {
        int[] out = new int[64];
        for (int i = 0; i < 64; i++) out[i] = colorAt(brand, i);
        return out;
    }

    /** Display-ordered 64-color wheel grouped across cool blues, greens, warm golds/reds, and purples. */
    public static int[] groupedPalette(Brand brand) {
        int[] out = new int[64];
        // On Hyundai's wheel, index 43..51 are Blues/Cyans, 36..42 Greens/Teals, 14..35 Reds/Golds, 52..63 & 0..13 Purples/Magentas.
        // Keeping contiguous ring rotation puts Cool Blues first cleanly across both wheels.
        int start = brand == Brand.KIA ? 54 : 43;
        for (int i = 0; i < 64; i++) out[i] = colorAt(brand, (start + i) % 64);
        return out;
    }

    public static int nearestIndex(Brand brand, int rgb) {
        int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
        int best = 0;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < 64; i++) {
            int c = colorAt(brand, i);
            int dr = r - ((c >> 16) & 0xff);
            int dg = g - ((c >> 8) & 0xff);
            int db = b - (c & 0xff);
            long d = (long) dr * dr + (long) dg * dg + (long) db * db;
            if (d < bestDist) {
                bestDist = d;
                best = i;
                if (d == 0) break;
            }
        }
        return best;
    }

    public static int nearestColor(Brand brand, int rgb) {
        return colorAt(brand, nearestIndex(brand, rgb));
    }

    public static String formatHex(int rgb) {
        return String.format(Locale.US, "#%06X", rgb & 0xffffff);
    }

    public static int parseHex(String text) {
        if (text == null) throw new IllegalArgumentException("Expected #RRGGBB color");
        String clean = text.trim();
        if (clean.startsWith("#")) clean = clean.substring(1);
        if (!clean.matches("[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Invalid color: " + text);
        return Integer.parseInt(clean, 16) & 0xffffff;
    }

    public static String colorName(int rgb) {
        int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
        if (r >= 240 && g >= 240 && b >= 240) return "Warm White";
        if (b >= 220 && g >= 160 && r < 60) return "Cyan";
        if (b >= 220 && g >= 110 && r < 110) return "Ice Blue";
        if (b >= 200 && r < 90 && g < 110) return "Cobalt";
        if (g >= 200 && b >= 160 && r < 140) return "Cool Teal";
        if (g >= 200 && r >= 170 && b >= 140) return "Mint";
        if (r >= 220 && g >= 170 && b < 160) return "Gold";
        if (r >= 220 && g >= 100 && g < 170 && b < 140) return "Warm Coral";
        if (r >= 220 && g < 100 && b < 90) return "Crimson";
        if (r >= 200 && b >= 100 && g < 120) return "Magenta";
        if (b >= 200 && r >= 110 && g < 80) return "Violet";
        return formatHex(rgb);
    }

    /** Converts UI level (0 = off, 1..10 = brightness) to MICOM raw byte (1 = off, 2..11). */
    public static int levelToRaw(int level) {
        if (level <= 0) return SML_OFF;
        return Math.min(10, level) + 1;
    }

    /** Converts MICOM raw byte (1 = off, 2..11) to UI level (0..10). */
    public static int rawToLevel(int raw) {
        if (raw <= SML_OFF) return 0;
        return Math.min(10, raw - 1);
    }

    /** Encodes 24-bit RGB into three 2-byte big-endian channel arrays matching VehicleInfoManager.setSMLRGB. */
    public static byte[][] encodeRgb(int rgb) {
        int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
        return new byte[][]{
            {(byte) (r >> 8), (byte) r},
            {(byte) (g >> 8), (byte) g},
            {(byte) (b >> 8), (byte) b}
        };
    }

    /** Parsed reply from SYS_SML_STATUS_M_RESP (com.mobis.intent.action.SML_STATUS_CHANGED / SystemService:D). */
    public static final class SmlStatus {
        public final int r, g, b, brightRaw, dimDarkRaw, driveModeRaw;

        public SmlStatus(int r, int g, int b, int brightRaw, int dimDarkRaw, int driveModeRaw) {
            this.r = r & 0xff;
            this.g = g & 0xff;
            this.b = b & 0xff;
            this.brightRaw = brightRaw & 0xff;
            this.dimDarkRaw = dimDarkRaw & 0xff;
            this.driveModeRaw = driveModeRaw & 0xff;
        }

        public int rgb() { return (r << 16) | (g << 8) | b; }
        public int level() { return rawToLevel(brightRaw); }
        public boolean dimmedInDark() { return dimDarkRaw == SML_ON; }
        public boolean driveModeLinked() { return driveModeRaw == SML_ON; }
        public boolean validEquipment() { return brightRaw >= SML_OFF && brightRaw <= 11; }

        @Override public boolean equals(Object o) {
            if (!(o instanceof SmlStatus)) return false;
            SmlStatus s = (SmlStatus) o;
            return r == s.r && g == s.g && b == s.b && brightRaw == s.brightRaw
                && dimDarkRaw == s.dimDarkRaw && driveModeRaw == s.driveModeRaw;
        }

        @Override public int hashCode() {
            return rgb() ^ (brightRaw << 24) ^ (dimDarkRaw << 28) ^ (driveModeRaw << 30);
        }

        @Override public String toString() {
            return formatHex(rgb()) + " lv=" + level() + " dim=" + dimDarkRaw + " drv=" + driveModeRaw;
        }
    }

    public static SmlStatus parseSmlStatusBytes(byte[] bytes) {
        if (bytes == null || bytes.length < 9) return null;
        int r = ((bytes[0] & 0xff) << 8) | (bytes[1] & 0xff);
        int g = ((bytes[2] & 0xff) << 8) | (bytes[3] & 0xff);
        int b = ((bytes[4] & 0xff) << 8) | (bytes[5] & 0xff);
        return new SmlStatus(r, g, b, bytes[6] & 0xff, bytes[7] & 0xff, bytes[8] & 0xff);
    }

    /** Parses a SystemService:D logcat line containing "SYS_SML_STATUS_M_RESP: 0x.. 0x.. ..." (or 9+ hex tokens). */
    public static SmlStatus parseSmlStatusLine(String line) {
        if (line == null) return null;
        int marker = line.indexOf("SYS_SML_STATUS_M_RESP");
        String scan = marker >= 0 ? line.substring(marker) : line;
        int colon = scan.indexOf(':');
        if (marker >= 0 && colon >= 0) scan = scan.substring(colon + 1);
        List<Integer> bytes = new ArrayList<>();
        for (String tok : scan.trim().split("[\\s,;]+")) {
            if (tok.isEmpty()) continue;
            String hex = tok.startsWith("0x") || tok.startsWith("0X") ? tok.substring(2) : tok;
            if (!hex.matches("[0-9a-fA-F]{1,2}")) continue;
            bytes.add(Integer.parseInt(hex, 16));
        }
        if (bytes.size() < 9) return null;
        byte[] raw = new byte[9];
        for (int i = 0; i < 9; i++) raw[i] = (byte) (int) bytes.get(i);
        return parseSmlStatusBytes(raw);
    }

    public static boolean validEquipment(SmlStatus status) {
        return status != null && status.validEquipment();
    }

    public static Properties encodeOwnerState(SmlStatus s, boolean dirty) {
        Properties p = new Properties();
        p.setProperty("r", Integer.toString(s.r));
        p.setProperty("g", Integer.toString(s.g));
        p.setProperty("b", Integer.toString(s.b));
        p.setProperty("brightRaw", Integer.toString(s.brightRaw));
        p.setProperty("dimDarkRaw", Integer.toString(s.dimDarkRaw));
        p.setProperty("driveModeRaw", Integer.toString(s.driveModeRaw));
        p.setProperty("dirty", Boolean.toString(dirty));
        return p;
    }

    public static final class OwnerState {
        public final SmlStatus status;
        public final boolean dirty;
        public OwnerState(SmlStatus status, boolean dirty) { this.status = status; this.dirty = dirty; }
    }

    public static OwnerState decodeOwnerState(Properties p) {
        if (p == null) return null;
        try {
            if (!p.containsKey("brightRaw") || !p.containsKey("r")) return null;
            SmlStatus s = new SmlStatus(
                Integer.parseInt(p.getProperty("r")),
                Integer.parseInt(p.getProperty("g")),
                Integer.parseInt(p.getProperty("b")),
                Integer.parseInt(p.getProperty("brightRaw")),
                Integer.parseInt(p.getProperty("dimDarkRaw", "2")),
                Integer.parseInt(p.getProperty("driveModeRaw", "1"))
            );
            return new OwnerState(s, "true".equals(p.getProperty("dirty")));
        } catch (RuntimeException e) { return null; }
    }

    /** Computed lighting target from active mode and vehicle signals. */
    public static final class Target {
        public final boolean active;
        public final int rgb, level;
        public final String summary, detail;

        public Target(boolean active, int rgb, int level, String summary, String detail) {
            this.active = active;
            this.rgb = rgb & 0xffffff;
            this.level = Math.max(0, Math.min(10, level));
            this.summary = summary;
            this.detail = detail;
        }
    }

    /** Formats a tenths-Celsius temperature in either °F or °C for UI and status sentences. */
    public static String formatTemp(int tenthsC, boolean fahrenheit) {
        if (fahrenheit) return Math.round((tenthsC / 10.0) * 9.0 / 5.0 + 32.0) + " °F";
        if (tenthsC == 0) return "0 °C";
        if (tenthsC % 10 == 0) return (tenthsC / 10) + " °C";
        return String.format(Locale.US, "%.1f °C", tenthsC / 10.0);
    }

    /** Steps a tenths-Celsius threshold by 1 degree in the displayed unit, clamped to [min, max]. */
    public static int stepTemp(int tenthsC, int delta, boolean fahrenheit, int minTenthsC, int maxTenthsC) {
        int next;
        if (fahrenheit) {
            int f = (int) Math.round((tenthsC / 10.0) * 9.0 / 5.0 + 32.0) + delta;
            double lo = (f - 0.5 - 32.0) * 5.0 / 9.0, hi = (f + 0.5 - 32.0) * 5.0 / 9.0;
            long whole = (long) Math.ceil(lo - 1e-9);
            next = (whole < hi - 1e-9 && Math.round(whole * 9.0 / 5.0 + 32.0) == f)
                ? (int) (whole * 10L)
                : (int) Math.round((f - 32.0) * 50.0 / 9.0);
        } else {
            next = ((int) Math.round(tenthsC / 10.0) + delta) * 10;
        }
        return Math.max(minTenthsC, Math.min(maxTenthsC, next));
    }

    /** Parses "HH:MM", "HHMM", "HH:MM:SS", or "h:MM AM/PM" into minutes [0..1439], or -1 when invalid. */
    public static int parseClockMinutes(String text) {
        if (text == null) return -1;
        String upper = text.trim().toUpperCase(Locale.US);
        boolean hasAm = upper.contains("AM");
        boolean hasPm = upper.contains("PM");
        String digits = upper.replaceAll("[^0-9]", "");
        if (digits.length() == 14) {
            // yyyyMMddHHmmss
            digits = digits.substring(8, 12);
        } else if (digits.length() == 6) {
            // HHmmss
            digits = digits.substring(0, 4);
        } else if (digits.length() == 3) {
            digits = "0" + digits;
        } else if (digits.length() != 4) {
            return -1;
        }
        int hour = Integer.parseInt(digits.substring(0, 2));
        int minute = Integer.parseInt(digits.substring(2, 4));
        if (minute < 0 || minute > 59) return -1;
        if (hasAm || hasPm) {
            if (hour < 1 || hour > 12) return -1;
            if (hasAm) {
                hour = (hour == 12) ? 0 : hour;
            } else {
                hour = (hour == 12) ? 12 : hour + 12;
            }
        } else if (hour < 0 || hour > 23) {
            return -1;
        }
        return hour * 60 + minute;
    }

    /**
     * Sunrise/sunset transition fraction in [0.0 (Day) .. 1.0 (Night)], or null when valid
     * sunrise and sunset times aren't available (telling Day and Night to fall back to theme/sensor).
     */
    public static Double sunsetFadeFraction(int nowMinutes, int sunriseMinutes, int sunsetMinutes) {
        if (nowMinutes < 0 || nowMinutes >= 1440 || sunriseMinutes < 0 || sunsetMinutes <= sunriseMinutes) return null;
        int w = SUN_FADE_HALF_WINDOW_MIN;
        if (nowMinutes < sunriseMinutes - w || nowMinutes >= sunsetMinutes + w) return 1.0;
        if (nowMinutes < sunriseMinutes + w) return 1.0 - ((double) (nowMinutes - (sunriseMinutes - w)) / (2 * w));
        if (nowMinutes <= sunsetMinutes - w) return 0.0;
        return (double) (nowMinutes - (sunsetMinutes - w)) / (2 * w);
    }

    public static Target manualTarget(Brand brand, int desiredRgb, int level) {
        int rgb = nearestColor(brand, desiredRgb);
        int lv = Math.max(0, Math.min(10, level));
        return new Target(true, rgb, lv,
            "Manual · " + colorName(rgb) + " · Lv " + lv,
            "Manual mode is active with " + colorName(rgb) + " at brightness " + lv + ".");
    }

    /** Day and Night using sunrise/sunset fade when enabled and fresh, or car theme/sensor fallback. */
    public static Target dayNightTarget(Brand brand, boolean carNight, boolean sunsetFade, Double fadeFraction,
                                        int dayRgb, int dayLevel, int nightRgb, int nightLevel) {
        int chosenRgb;
        int level;
        String phase;
        String sentence;
        if (sunsetFade && fadeFraction != null) {
            double t = Math.max(0.0, Math.min(1.0, fadeFraction));
            chosenRgb = nearestColor(brand, blendRgb(dayRgb, nightRgb, t));
            level = (int) Math.round(dayLevel * (1.0 - t) + nightLevel * t);
            if (t <= 0.05) {
                phase = "Day";
                sentence = "Day color is on based on today's sunrise and sunset times.";
            } else if (t >= 0.95) {
                phase = "Night";
                sentence = "Night color is on based on today's sunrise and sunset times.";
            } else {
                phase = "Fade";
                sentence = "Fading smoothly between your Day and Night colors.";
            }
        } else {
            chosenRgb = nearestColor(brand, carNight ? nightRgb : dayRgb);
            level = carNight ? nightLevel : dayLevel;
            phase = carNight ? "Night" : "Day";
            sentence = carNight
                ? "The car's Night theme is active, so your Night color is on."
                : "The car's Day theme is active, so your Day color is on.";
        }
        return new Target(true, chosenRgb, level,
            phase + " · " + colorName(chosenRgb) + " · Lv " + level,
            sentence);
    }

    /** Classifies DATC climate fields into Off, Defrost, Heating, or Cooling cleanly on 260903+. */
    public static ClimateState classifyClimate(int opStatus, int modeDrive, int modePass,
                                             int autoMode, int autoDefogBlink, int heatOnOff, int acOnOff) {
        if (opStatus == 0) return ClimateState.OFF;
        if (isDefrostMode(modeDrive, autoMode) || isDefrostMode(modePass, autoMode) || autoDefogBlink != 0) {
            return ClimateState.DEFROST;
        }
        if (heatOnOff != 0) return ClimateState.HEATING;
        if (acOnOff != 0) return ClimateState.COOLING;
        return ClimateState.OFF;
    }

    private static boolean isDefrostMode(int mode, int autoMode) {
        // DATCConstants$DatcModeDisp: 5=DEF, 7=DEFVENT, 8=DEFFL/MODE_AUTO, 9..13=DEF variants.
        return mode == 5 || mode == 7 || (mode == 8 && autoMode == 0) || (mode >= 9 && mode <= 13);
    }

    /** Parses DATC temperature unit (true = Fahrenheit (2), false = Celsius (1/0), null = unknown). */
    public static Boolean parseDatcFahrenheit(String dump) {
        if (dump == null) return null;
        for (String line : dump.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("mTempUnit:") || trimmed.startsWith("mTempUnit ")) {
                String val = trimmed.replaceAll("[^0-9]", "");
                if ("2".equals(val)) return Boolean.TRUE;
                if ("1".equals(val) || "0".equals(val)) return Boolean.FALSE;
            }
        }
        return null;
    }

    /** Converts ReadAndSavePresentWeatherInfo.getValue() using getUnit() (2 = F, 1/0 = C). */
    public static Double weatherTempToCelsius(double raw, int unit) {
        if (Double.isNaN(raw) || Double.isInfinite(raw)) return null;
        double c = (unit == 2) ? (raw - 32.0) * 5.0 / 9.0 : raw;
        return (c >= -80.0 && c <= 80.0) ? c : null;
    }

    /**
     * Extracts ONLY anonymous weather fields (icon, unit/temp, rainfall/snow, sunrise/sunset) from
     * ReadAndSaveWeatherParcelable. Location titles and location codes are never read or stored.
     */
    public static WeatherReport parseWeatherContainer(Object raw, long nowMs) {
        if (raw == null) return null;
        try {
            Object present = findFirstPresentWeather(raw);
            if (present == null) return null;
            int icon = invokeInt(present, "getIcon", -1);
            int unit = invokeInt(present, "getUnit", 1);
            double rawVal = invokeDouble(present, "getValue", Double.NaN);
            double rainMm = invokeDouble(present, "getRainfallAmount", 0.0);
            double rainProb = invokeDouble(present, "getRainfallProbability", 0.0);
            double snowMm = invokeDouble(present, "getSnowfallAmount", 0.0);
            String sunrise = invokeString(present, "getSunriseTime");
            String sunset = invokeString(present, "getSunsetTime");
            WeatherCondition cond = classifyTelematicsWeather(rainMm, rainProb, snowMm, icon, null);
            Double tempC = weatherTempToCelsius(rawVal, unit);
            if (cond == WeatherCondition.UNKNOWN && tempC == null
                    && sunrise == null && sunset == null) {
                return null;
            }
            return new WeatherReport(cond, tempC, sunrise, sunset, nowMs);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object findFirstPresentWeather(Object obj) throws Exception {
        if (obj == null) return null;
        if (obj instanceof List<?>) {
            for (Object elem : (List<?>) obj) {
                Object found = findFirstPresentWeather(elem);
                if (found != null) return found;
            }
            return null;
        }
        String clsName = obj.getClass().getName();
        if (clsName.endsWith("ReadAndSaveWeatherInfoList")) {
            return findFirstPresentWeather(obj.getClass().getMethod("getmReadAndSaveWeatherInfoList").invoke(obj));
        }
        if (clsName.endsWith("ReadAndSaveWeatherInfo")) {
            return findFirstPresentWeather(obj.getClass().getMethod("getmReadAndSavePresentWeatherInfoList").invoke(obj));
        }
        if (clsName.endsWith("ReadAndSavePresentWeatherInfo")) {
            return obj;
        }
        try {
            Object list = obj.getClass().getMethod("getmReadAndSaveWeatherInfoList").invoke(obj);
            if (list != null) return findFirstPresentWeather(list);
        } catch (NoSuchMethodException ignored) { }
        try {
            Object list = obj.getClass().getMethod("getmReadAndSavePresentWeatherInfoList").invoke(obj);
            if (list != null) return findFirstPresentWeather(list);
        } catch (NoSuchMethodException ignored) { }
        try {
            obj.getClass().getMethod("getIcon");
            return obj;
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static int invokeInt(Object obj, String method, int fallback) {
        try {
            return ((Number) obj.getClass().getMethod(method).invoke(obj)).intValue();
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static double invokeDouble(Object obj, String method, double fallback) {
        try {
            return ((Number) obj.getClass().getMethod(method).invoke(obj)).doubleValue();
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static String invokeString(Object obj, String method) {
        try {
            Object v = obj.getClass().getMethod(method).invoke(obj);
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Parses integer field like "mOpStatus: 1" from `dumpsys com.hkmc.micom.datc`, or fallback if absent. */
    public static int parseDumpInt(String dump, String field, int fallback) {
        if (dump == null) return fallback;
        for (String line : dump.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(field + ":")) {
                String num = trimmed.substring(field.length() + 1).trim();
                try { return Integer.parseInt(num); } catch (NumberFormatException ignored) { }
            }
        }
        return fallback;
    }

    /** Builds Climate mode target from the current cabin heating, cooling, defrost, or off state. */
    public static Target climateTarget(Brand brand, ClimateState state, int heatRgb, int coolRgb,
                                       int defrostRgb, int offRgb, int level) {
        ClimateState s = state == null ? ClimateState.OFF : state;
        int chosen;
        String sentence;
        switch (s) {
            case HEATING:
                chosen = heatRgb;
                sentence = "Cabin heating is running, so the Heating color is on.";
                break;
            case COOLING:
                chosen = coolRgb;
                sentence = "Cabin cooling is running, so the Cooling color is on.";
                break;
            case DEFROST:
                chosen = defrostRgb;
                sentence = "Defrost is running, so the Defrost color is on.";
                break;
            case OFF:
            default:
                chosen = offRgb;
                sentence = "Climate control is off, so the Climate Off color is on.";
                break;
        }
        int rgb = nearestColor(brand, chosen);
        int lv = Math.max(0, Math.min(10, level));
        return new Target(true, rgb, lv,
            s.label + " · " + colorName(rgb) + " · Lv " + lv,
            sentence);
    }

    /** Connected-services weather snapshot mapped cleanly to Policy categories. */
    public static final class WeatherReport {
        public final WeatherCondition condition;
        public final Double tempC;
        public final String sunriseHHMM, sunsetHHMM;
        public final long updatedAtMs;

        public WeatherReport(WeatherCondition condition, Double tempC, String sunriseHHMM, String sunsetHHMM, long updatedAtMs) {
            this.condition = condition == null ? WeatherCondition.UNKNOWN : condition;
            this.tempC = tempC;
            this.sunriseHHMM = sunriseHHMM;
            this.sunsetHHMM = sunsetHHMM;
            this.updatedAtMs = updatedAtMs;
        }
    }

    public static boolean isWeatherFresh(WeatherReport report, long nowMs) {
        return report != null && report.updatedAtMs > 0 && nowMs >= report.updatedAtMs
            && (nowMs - report.updatedAtMs) <= WEATHER_CACHE_FRESH_MS;
    }

    /** Classifies telematics weather fields into Snow, Rain, Clouds, Clear, or Unknown. */
    public static WeatherCondition classifyTelematicsWeather(double rainMm, double rainProb, double snowMm,
                                                             int icon, String description) {
        WeatherCondition parsed = parseWeatherCondition(description);
        if (snowMm > 0 || parsed == WeatherCondition.SNOW) return WeatherCondition.SNOW;
        if (rainMm > 0 || rainProb >= 50.0 || parsed == WeatherCondition.RAIN || parsed == WeatherCondition.STORM) {
            return WeatherCondition.RAIN;
        }
        if (parsed == WeatherCondition.CLOUDS || parsed == WeatherCondition.FOG) return WeatherCondition.CLOUDS;
        if (parsed == WeatherCondition.CLEAR) return WeatherCondition.CLEAR;
        // Common HKMC telematics weather icon ranges fall back cleanly when text is empty.
        if (icon >= 13 && icon <= 16) return WeatherCondition.SNOW;
        if (icon >= 7 && icon <= 12) return WeatherCondition.RAIN;
        if (icon >= 3 && icon <= 6) return WeatherCondition.CLOUDS;
        if (icon == 1 || icon == 2) return WeatherCondition.CLEAR;
        return WeatherCondition.UNKNOWN;
    }

    /**
     * Weather mode target: uses fresh (< 1h) connected-services weather when present, otherwise picks
     * Cold, Mild, or Hot from the outside temperature using the owner's editable thresholds.
     */
    public static Target weatherTarget(Brand brand, WeatherReport report, boolean fresh, Double outsideTempC,
                                       int coldTenthsC, int hotTenthsC, boolean fahrenheit,
                                       int clearRgb, int cloudyRgb, int rainRgb, int snowRgb,
                                       int coldRgb, int mildRgb, int hotRgb, int level) {
        int chosenRgb;
        String bucket;
        String sentence;
        if (fresh && report != null && report.condition != WeatherCondition.UNKNOWN) {
            switch (report.condition) {
                case SNOW:
                    chosenRgb = snowRgb; bucket = "Snow";
                    sentence = "Connected-services weather reports snow.";
                    break;
                case RAIN: case STORM:
                    chosenRgb = rainRgb; bucket = "Rain";
                    sentence = "Connected-services weather reports rain.";
                    break;
                case CLOUDS: case FOG:
                    chosenRgb = cloudyRgb; bucket = "Cloudy";
                    sentence = "Connected-services weather reports cloudy skies.";
                    break;
                case CLEAR:
                default:
                    chosenRgb = clearRgb; bucket = "Clear";
                    sentence = "Connected-services weather reports clear skies.";
                    break;
            }
        } else if (outsideTempC != null) {
            int tenths = (int) Math.round(outsideTempC * 10.0);
            String tempLabel = formatTemp(tenths, fahrenheit);
            if (tenths <= coldTenthsC) {
                chosenRgb = coldRgb; bucket = "Cold";
                sentence = "Outside temperature is " + tempLabel + ", which is in your Cold range.";
            } else if (tenths >= hotTenthsC) {
                chosenRgb = hotRgb; bucket = "Hot";
                sentence = "Outside temperature is " + tempLabel + ", which is in your Hot range.";
            } else {
                chosenRgb = mildRgb; bucket = "Mild";
                sentence = "Outside temperature is " + tempLabel + ", which is in your Mild range.";
            }
        } else {
            chosenRgb = mildRgb; bucket = "Mild";
            sentence = "Outside temperature isn't available yet, so using your Mild color.";
        }
        int rgb = nearestColor(brand, chosenRgb);
        int lv = Math.max(0, Math.min(10, level));
        return new Target(true, rgb, lv,
            bucket + " · " + colorName(rgb) + " · Lv " + lv,
            sentence);
    }

    public static WeatherCondition parseWeatherCondition(String description) {
        if (description == null) return WeatherCondition.UNKNOWN;
        String s = description.toLowerCase(Locale.US);
        if (s.contains("snow") || s.contains("sleet") || s.contains("ice") || s.contains("blizzard")) return WeatherCondition.SNOW;
        if (s.contains("thunder") || s.contains("storm") || s.contains("squall")) return WeatherCondition.STORM;
        if (s.contains("rain") || s.contains("shower") || s.contains("drizzle")) return WeatherCondition.RAIN;
        if (s.contains("fog") || s.contains("mist") || s.contains("haze")) return WeatherCondition.FOG;
        if (s.contains("cloud") || s.contains("overcast")) return WeatherCondition.CLOUDS;
        if (s.contains("clear") || s.contains("sun")) return WeatherCondition.CLEAR;
        return WeatherCondition.UNKNOWN;
    }

    /** Returns every editable config key/value belonging to one mode's factory defaults. */
    public static Map<String, String> defaultsForMode(Mode mode) {
        Map<String, String> out = new LinkedHashMap<>();
        if (mode == null) return out;
        switch (mode) {
            case MANUAL:
                out.put("ambientColor", "#00B4FF");
                out.put("ambientLevel", "7");
                break;
            case DAY_NIGHT:
                out.put("ambientDayColor", "#00B4FF");
                out.put("ambientDayLevel", "7");
                out.put("ambientNightColor", "#FF7D73");
                out.put("ambientNightLevel", "4");
                out.put("ambientSunsetFade", "true");
                break;
            case CLIMATE:
                out.put("ambientClimateHeatColor", "#FF7D73");
                out.put("ambientClimateCoolColor", "#00B4FF");
                out.put("ambientClimateDefrostColor", "#00D9F5");
                out.put("ambientClimateOffColor", "#8E7BFF");
                out.put("ambientClimateLevel", "6");
                break;
            case WEATHER:
                out.put("ambientWeatherClearColor", "#FFCA52");
                out.put("ambientWeatherCloudyColor", "#8FA8FF");
                out.put("ambientWeatherRainColor", "#1E6FFF");
                out.put("ambientWeatherSnowColor", "#5BC0FF");
                out.put("ambientWeatherColdColor", "#00B4FF");
                out.put("ambientWeatherMildColor", "#38D5B4");
                out.put("ambientWeatherHotColor", "#FF7D73");
                out.put("ambientWeatherColdC", Integer.toString(DEFAULT_COLD_TENTHS_C));
                out.put("ambientWeatherHotC", Integer.toString(DEFAULT_HOT_TENTHS_C));
                out.put("ambientWeatherLevel", "6");
                break;
            case MUSIC:
                out.put("ambientMusicStyle", "pulse");
                out.put("ambientMusicDrive", "pause");
                out.put("ambientMusicLevel", "6");
                break;
            case OFF:
            default:
                break;
        }
        return out;
    }

    private static int blendRgb(int c0, int c1, double t) {
        int r = (int) Math.round(((c0 >> 16) & 0xff) * (1 - t) + ((c1 >> 16) & 0xff) * t);
        int g = (int) Math.round(((c0 >> 8) & 0xff) * (1 - t) + ((c1 >> 8) & 0xff) * t);
        int b = (int) Math.round((c0 & 0xff) * (1 - t) + (c1 & 0xff) * t);
        return ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff);
    }

    /** True when Android's AudioManager mode or voice PCM indicates an active or ringing phone call. */
    public static boolean isPhoneCallAudioMode(int audioMode) {
        return isPhoneCallAudioMode(audioMode, false);
    }

    public static boolean isPhoneCallAudioMode(int audioMode, boolean voicePcmRunning) {
        return voicePcmRunning
            || audioMode == AUDIO_MODE_RINGTONE
            || audioMode == AUDIO_MODE_IN_CALL
            || audioMode == AUDIO_MODE_IN_COMMUNICATION;
    }

    /**
     * Extracts normalized overall energy and 4 musical bands (bass, low-mid, high-mid, treble) in
     * [0..1] from Android {@code Visualizer.getFft} / {@code getWaveForm} byte arrays. Returns
     * {@code null} when the buffer is invalid or carries only silence (e.g. audio bypassing the
     * Android output mix), so callers can fall back to timed lighting cleanly.
     */
    public static double[] analyzeVisualizerFft(byte[] fft, byte[] waveform) {
        if (fft == null || fft.length < 16) return null;
        int numBins = fft.length / 2;
        if (numBins < 5) return null;
        int b1End = Math.max(2, numBins / 8);
        int b2End = Math.max(b1End + 1, (numBins * 3) / 10);
        int b3End = Math.max(b2End + 1, (numBins * 6) / 10);
        double[] sumSq = new double[4];
        int[] counts = new int[4];
        double maxBinMag = 0.0;
        for (int k = 1; k < numBins; k++) {
            double re = fft[2 * k];
            double im = fft[2 * k + 1];
            double magSq = re * re + im * im;
            double mag = Math.sqrt(magSq);
            if (mag > maxBinMag) maxBinMag = mag;
            int band = k <= b1End ? 0 : k <= b2End ? 1 : k <= b3End ? 2 : 3;
            sumSq[band] += magSq;
            counts[band]++;
        }

        double waveRms = 0.0;
        if (waveform != null && waveform.length > 0) {
            double sum = 0.0;
            for (byte b : waveform) sum += (b & 0xff);
            double mean = sum / waveform.length;
            double acSq = 0.0;
            for (byte b : waveform) {
                double ac = (b & 0xff) - mean;
                acSq += ac * ac;
            }
            waveRms = Math.sqrt(acSq / waveform.length);
        }
        if (maxBinMag < 2.0 && waveRms < 1.5) return null;

        double[] bands = new double[4];
        for (int b = 0; b < 4; b++) {
            double rms = counts[b] > 0 ? Math.sqrt(sumSq[b] / counts[b]) : 0.0;
            bands[b] = Math.min(1.0, Math.sqrt(rms / 64.0));
        }
        double waveEnergy = Math.min(1.0, Math.sqrt(waveRms / 48.0));
        double weighted = 0.35 * bands[0] + 0.30 * bands[1] + 0.20 * bands[2] + 0.15 * bands[3];
        double overall = Math.max(0.05, Math.min(1.0, Math.max(waveEnergy, weighted)));
        return new double[]{overall, bands[0], bands[1], bands[2], bands[3]};
    }

    /**
     * Stateful Music smoothing: Park-only lighting animated while audio plays (Pause in Drive) or a gentle
     * Slow Color Cycle in Drive that never flashes, with bounded brightness slew and band dwell.
     */
    public static final class MusicTracker {
        private static final int[] SPECTRUM_RGB = {0xFF4F6D, 0xFFAA00, 0x38D5B4, 0xAA4FFF, 0x00B4FF};
        private double smoothedEnergy;
        private int currentBand;
        private int currentLevel;
        private int cycleStep;
        private boolean started;
        private long lastColorChangeMs = Long.MIN_VALUE / 2;
        private long lastCycleStepMs = Long.MIN_VALUE / 2;

        public void reset() {
            smoothedEnergy = 0;
            currentBand = 0;
            currentLevel = 0;
            cycleStep = 0;
            started = false;
            lastColorChangeMs = Long.MIN_VALUE / 2;
            lastCycleStepMs = Long.MIN_VALUE / 2;
        }

        public Target update(Brand brand, MusicStyle style, MusicDriveBehavior driveBehavior,
                             int baseRgb, int maxLevel, boolean parked,
                             double rawEnergy, double[] bands, long nowMs) {
            int cap = Math.max(1, Math.min(10, maxLevel));
            if (!parked) {
                if (driveBehavior == MusicDriveBehavior.SLOW_CYCLE) {
                    if (lastCycleStepMs == Long.MIN_VALUE / 2) lastCycleStepMs = nowMs;
                    else if (nowMs - lastCycleStepMs >= MUSIC_CYCLE_STEP_MS) {
                        cycleStep = (cycleStep + 1) % 64;
                        lastCycleStepMs = nowMs;
                    }
                    int[] wheel = groupedPalette(brand);
                    int rgb = wheel[cycleStep % wheel.length];
                    return new Target(true, rgb, cap,
                        "Slow Cycle · " + colorName(rgb) + " · Lv " + cap,
                        "Slow Color Cycle is gliding gently while in Drive.");
                }
                reset();
                return new Target(false, baseRgb, cap,
                    "Paused · Park only",
                    "Music lighting is paused in Drive and your car's saved lighting is restored until you park.");
            }
            lastCycleStepMs = Long.MIN_VALUE / 2;
            double clamped = Math.max(0.0, Math.min(1.0, rawEnergy));
            smoothedEnergy = smoothedEnergy * 0.72 + clamped * 0.28;
            int floor = Math.max(1, (int) Math.round(cap * 0.4));
            int desiredLevel = (int) Math.round(floor + (cap - floor) * smoothedEnergy);
            if (currentLevel == 0) currentLevel = floor;
            if (desiredLevel > currentLevel) currentLevel = Math.min(desiredLevel, currentLevel + MUSIC_MAX_LEVEL_SLEW);
            else if (desiredLevel < currentLevel) currentLevel = Math.max(desiredLevel, currentLevel - MUSIC_MAX_LEVEL_SLEW);
            currentLevel = Math.max(1, Math.min(cap, currentLevel));

            int chosenRgb = nearestColor(brand, baseRgb);
            if (style == MusicStyle.SLOW_SPECTRUM) {
                if (bands != null && bands.length > 0) {
                    if (!started) {
                        int bestBand = 0;
                        for (int i = 1; i < bands.length; i++) {
                            if (bands[i] > bands[bestBand]) bestBand = i;
                        }
                        currentBand = bestBand;
                        lastColorChangeMs = nowMs;
                        started = true;
                    } else if (nowMs - lastColorChangeMs >= MUSIC_COLOR_DWELL_MS) {
                        int bestBand = currentBand < bands.length ? currentBand : 0;
                        double currentVal = bands[bestBand];
                        for (int i = 0; i < bands.length; i++) {
                            if (bands[i] > bands[bestBand] && bands[i] >= currentVal * (1.0 + MUSIC_HYSTERESIS)) {
                                bestBand = i;
                            }
                        }
                        if (bestBand != currentBand) {
                            currentBand = bestBand;
                            lastColorChangeMs = nowMs;
                        }
                    }
                }
                chosenRgb = nearestColor(brand, SPECTRUM_RGB[currentBand % SPECTRUM_RGB.length]);
            }
            return new Target(true, chosenRgb, currentLevel,
                "Music · " + colorName(chosenRgb) + " · Lv " + currentLevel,
                "Music lighting is running while parked with " + style.label + " active.");
        }
    }

    /** Tracks rate limits (<= 10 Hz) and recent self-writes (3 s window) so manual menu edits win cleanly. */
    public static final class WriteTracker {
        private long lastWriteMs = Long.MIN_VALUE / 2;
        private int lastRgb = -1, lastBrightRaw = -1, lastDriveRaw = -1;

        public boolean canSend(long nowMs) {
            return nowMs - lastWriteMs >= MIN_COMMAND_GAP_MS;
        }

        public void recordRgb(int rgb, long nowMs) {
            lastWriteMs = nowMs;
            lastRgb = rgb & 0xffffff;
            lastDriveRaw = SML_OFF;
        }

        public void recordConfig(int mode, int data, long nowMs) {
            lastWriteMs = nowMs;
            if (mode == SML_MODE_BRIGHTNESS) lastBrightRaw = data;
            else if (mode == SML_MODE_DRIVE_LINK) lastDriveRaw = data;
        }

        /** True if observed status matches Tilepad's recent write OR still matches our applied color/level. */
        public boolean isSelfEcho(SmlStatus observed, long nowMs) {
            return isSelfEcho(observed, -1, -1, nowMs);
        }

        public boolean isSelfEcho(SmlStatus observed, int appliedRgb, int appliedLevel, long nowMs) {
            if (observed == null) return false;
            if (appliedRgb >= 0 && appliedLevel >= 0
                    && observed.rgb() == (appliedRgb & 0xffffff)
                    && observed.level() == appliedLevel
                    && !observed.driveModeLinked()) {
                return true;
            }
            if (nowMs - lastWriteMs > SELF_WRITE_WINDOW_MS) return false;
            if (lastRgb >= 0 && observed.rgb() != lastRgb) return false;
            if (lastBrightRaw >= 0 && observed.brightRaw != lastBrightRaw) return false;
            if (lastDriveRaw >= 0 && observed.driveModeRaw != lastDriveRaw) return false;
            return lastRgb >= 0 || lastBrightRaw >= 0 || lastDriveRaw >= 0;
        }

        public void reset() {
            lastWriteMs = Long.MIN_VALUE / 2;
            lastRgb = lastBrightRaw = lastDriveRaw = -1;
        }
    }

    /** One ordered command step for restoration or update testing. */
    public static final class SmlCommand {
        public enum Kind { CONFIG, RGB }
        public final Kind kind;
        public final int mode, data, rgb;

        public static SmlCommand config(int mode, int data) { return new SmlCommand(Kind.CONFIG, mode, data, 0); }
        public static SmlCommand rgb(int rgb) { return new SmlCommand(Kind.RGB, 0, 0, rgb & 0xffffff); }

        private SmlCommand(Kind kind, int mode, int data, int rgb) {
            this.kind = kind; this.mode = mode; this.data = data; this.rgb = rgb;
        }
    }

    /** Builds ordered restoration steps back to the owner's exact captured state (saved RGB + brightness + link). */
    public static List<SmlCommand> buildRestoreSteps(SmlStatus owner) {
        List<SmlCommand> steps = new ArrayList<>();
        if (owner == null) return steps;
        steps.add(SmlCommand.config(SML_MODE_DRIVE_LINK, SML_OFF));
        steps.add(SmlCommand.rgb(owner.rgb()));
        steps.add(SmlCommand.config(SML_MODE_BRIGHTNESS, owner.brightRaw));
        if (owner.driveModeLinked()) {
            steps.add(SmlCommand.config(SML_MODE_DRIVE_LINK, SML_ON));
        }
        return steps;
    }
}
