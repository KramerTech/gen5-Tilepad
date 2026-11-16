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

    @Test public void layoutIsTheDeviceNameWithItsRegionReplacedByBase() {
        // Each archived log's device and the layout it loaded.
        String[][] seen = {{"daudio_wp_daudioplus_neev23_us", "neev23"}, {"daudio_wp_daudioplus_neev_ca", "neev"},
            {"daudio_wp_daudioplus_cvev_us", "cvev"}, {"daudio_wp_daudioplus_cvev_ca", "cvev"},
            {"daudio_wp_daudioplus_mq422_us", "mq422"}, {"daudio_wp_daudioplus_dn822_us", "dn822"}};
        for (String[] s : seen) assertEquals("/system/usr/keylayout/daudio_wp_daudioplus_" + s[1] + "_base.kl", Evdev.layoutFor(s[0]));
    }

    @Test public void customKeyIsTheStarElseTheCarsOwnCustomKey() throws Exception {
        // Excerpts of the archived layouts. IONIQ 5 and EV6: ★ on the wheel at 41 and 43.
        String ioniq5 = "key 6     SHORTCUT_HOME\nkey 20    SHORTCUT_CUSTOM\nkey 34    CHANNEL_UP\nkey 41    SHORTCUT_SWRC_CUSTOM\nkey 43    SHORTCUT_SWRC_CUSTOM\n";
        assertEquals(43, Evdev.customKey(new StringReader(ioniq5)));
        // Sonata (DN8): no ★, its wheel's programmable key is the custom key at 4.
        String sonata = "key 1     POWER_AVN\nkey 2     SHORTCUT_MAP\nkey 4     SHORTCUT_CUSTOM\nkey 34    CHANNEL_UP\n";
        assertEquals(4, Evdev.customKey(new StringReader(sonata)));
        // Sorento (MQ4): no ★, a custom key at 18; commented lines don't count.
        String sorento = "#key 22    SHORTCUT_RADIO\nkey 18    SHORTCUT_CUSTOM\nkey 19    SHORTCUT_SETUP\n";
        assertEquals(18, Evdev.customKey(new StringReader(sorento)));
        // A wheel custom key that isn't 43 still wins over the head unit's.
        assertEquals(41, Evdev.customKey(new StringReader("key 20 SHORTCUT_CUSTOM\nkey 41 SHORTCUT_SWRC_CUSTOM\n")));
        assertEquals(43, Evdev.customKey(new StringReader("key 34 CHANNEL_UP\n")));
    }

    @Test public void keyTypeIsTheCustomKeysOnlyForShortcutCustom() throws Exception {
        String ioniq5 = "key 20    SHORTCUT_CUSTOM\nkey 41    SHORTCUT_SWRC_CUSTOM\nkey 43    SHORTCUT_SWRC_CUSTOM\n";
        assertEquals(Evdev.WHEEL_KEY_TYPE, Evdev.keyType(new StringReader(ioniq5), 43));
        assertEquals(Evdev.CUSTOM_KEY_TYPE, Evdev.keyType(new StringReader(ioniq5), 20));
        String sonata = "key 2     SHORTCUT_MAP\nkey 4     SHORTCUT_CUSTOM\nkey 34    CHANNEL_UP\n";
        assertEquals(Evdev.CUSTOM_KEY_TYPE, Evdev.keyType(new StringReader(sonata), 4));
        // A code the layout doesn't name (a KEY_CODE override) is treated as the star.
        assertEquals(Evdev.WHEEL_KEY_TYPE, Evdev.keyType(new StringReader(sonata), 43));
        assertEquals(Evdev.WHEEL_KEY_TYPE, Evdev.keyType(new StringReader("key 44 SHORTCUT_CUSTOM\n"), 4));
    }

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
