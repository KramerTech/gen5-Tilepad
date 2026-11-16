package com.tilepad.longstar;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/** Helpers for the Linux evdev interface as seen by 32-bit ARM userspace. */
public final class Evdev {
    /** struct input_event on 32-bit ARM: timeval (2 x 4 bytes), u16 type, u16 code, s32 value. */
    public static final int EVENT_SIZE = 16;
    public static final int EV_KEY = 1;

    private Evdev() {}

    public static int type(byte[] b, int off) { return u16(b, off + 8); }
    public static int code(byte[] b, int off) { return u16(b, off + 10); }
    public static int value(byte[] b, int off) {
        return (b[off + 12] & 0xff) | (b[off + 13] & 0xff) << 8
            | (b[off + 14] & 0xff) << 16 | (b[off + 15]) << 24;
    }

    private static int u16(byte[] b, int off) { return (b[off] & 0xff) | (b[off + 1] & 0xff) << 8; }

    /** Scan code 43: the steering-wheel ★ on the IONIQ 5 and EV6. */
    public static final int STAR = 43;

    /**
     * The key layout a head unit loads for its key device: the device name with its region
     * replaced by _base (daudio_wp_daudioplus_neev23_us: daudio_wp_daudioplus_neev23_base.kl).
     */
    public static String layoutFor(String deviceName) {
        int region = deviceName.lastIndexOf('_');
        return "/system/usr/keylayout/" + (region < 0 ? deviceName : deviceName.substring(0, region)) + "_base.kl";
    }

    /**
     * The key a hold opens Tilepad with, from that layout: ★ (43 as SHORTCUT_SWRC_CUSTOM), else
     * another steering-wheel custom key, else the custom key (SHORTCUT_CUSTOM: the Sonata's
     * programmable HOME, scan code 4), else 43. Stock runs a custom key's action only on a short
     * release, as the ★'s, so a hold doesn't also run it.
     */
    public static int customKey(Reader layout) throws IOException {
        BufferedReader in = new BufferedReader(layout);
        Integer wheel = null, custom = null;
        for (String line; (line = in.readLine()) != null; ) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 3 || !f[0].equals("key")) continue;
            int code;
            try { code = Integer.parseInt(f[1]); } catch (NumberFormatException e) { continue; }
            if (f[2].equals("SHORTCUT_SWRC_CUSTOM")) {
                if (code == STAR) return STAR;
                if (wheel == null) wheel = code;
            } else if (f[2].equals("SHORTCUT_CUSTOM") && custom == null) custom = code;
        }
        return wheel != null ? wheel : custom != null ? custom : STAR;
    }

    /**
     * Finds the /dev/input node for an exact device name in /proc/bus/input/devices
     * format ("N: Name=..." then "H: Handlers=... eventN"). Returns null if absent.
     */
    public static String findDevice(Reader procDevices, String name) throws IOException {
        BufferedReader in = new BufferedReader(procDevices);
        String wanted = "N: Name=\"" + name + "\"";
        boolean match = false;
        for (String line; (line = in.readLine()) != null; ) {
            if (line.startsWith("N: ")) {
                match = line.equals(wanted);
            } else if (match && line.startsWith("H: Handlers=")) {
                for (String h : line.substring(12).trim().split("\\s+")) {
                    if (h.startsWith("event")) return "/dev/input/" + h;
                }
            } else if (line.isEmpty()) {
                match = false;
            }
        }
        return null;
    }
}
