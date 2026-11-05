package com.tilepad.longstar;

import org.junit.Test;

import java.io.StringReader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class EvdevTest {
    /** Excerpt of /proc/bus/input/devices captured from this head unit. */
    private static final String DEVICES =
        "I: Bus=0006 Vendor=0123 Product=1234 Version=0000\n"
        + "N: Name=\"daudio_wp_daudioplus_neev23_us\"\n"
        + "P: Phys=\n"
        + "H: Handlers=sysrq event1 \n"
        + "B: EV=3\n"
        + "\n"
        + "I: Bus=0006 Vendor=0123 Product=2345 Version=0000\n"
        + "N: Name=\"daudio_wp_daudioplus_neev23_us_can\"\n"
        + "H: Handlers=sysrq event2 \n"
        + "\n"
        + "I: Bus=0006 Vendor=0123 Product=3456 Version=0000\n"
        + "N: Name=\"daudio_wp_daudioplus_neev23_us_knob\"\n"
        + "H: Handlers=event3 \n";

    @Test public void findsExactNameNotPrefixMatches() throws Exception {
        assertEquals("/dev/input/event1",
            Evdev.findDevice(new StringReader(DEVICES), "daudio_wp_daudioplus_neev23_us"));
        assertEquals("/dev/input/event3",
            Evdev.findDevice(new StringReader(DEVICES), "daudio_wp_daudioplus_neev23_us_knob"));
        assertNull(Evdev.findDevice(new StringReader(DEVICES), "missing"));
    }

    @Test public void decodesTuneEnterRecord() {
        // [1790893058.651362] /dev/input/event1: 0001 0011 00000001
        byte[] ev = {
            (byte) 0xa2, (byte) 0x9b, 0x2c, 0x6a, 0x72, (byte) 0xef, 0x09, 0x00,
            0x01, 0x00, 0x11, 0x00, 0x01, 0x00, 0x00, 0x00};
        assertEquals(Evdev.EV_KEY, Evdev.type(ev, 0));
        assertEquals(17, Evdev.code(ev, 0));
        assertEquals(1, Evdev.value(ev, 0));
        ev[12] = (byte) 0xff; ev[13] = (byte) 0xff; ev[14] = (byte) 0xff; ev[15] = (byte) 0xff;
        assertEquals(-1, Evdev.value(ev, 0));
    }
}
