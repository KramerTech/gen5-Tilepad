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
