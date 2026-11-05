package com.tilepad.tweakcore;

import org.junit.Test;
import java.text.SimpleDateFormat;
import java.util.*;
import static org.junit.Assert.*;

public class PolicyTest {
    private static long utc(String date) throws Exception { SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US); f.setTimeZone(TimeZone.getTimeZone("UTC")); return f.parse(date).getTime(); }
    @Test public void everyTweakDefaultsOffAndOptionsAreValidated() {
        Config c = new Config(new Properties()); for (String id : Config.IDS) assertFalse(c.enabled(id));
        assertEquals(100, c.number("threshold")); assertEquals("us", c.get("region"));
        for (String[] pair : new String[][]{{"threshold", "601"}, {"driverLevel", "0"}, {"region", "$(reboot)"}, {"dst", "1"}, {"path", "/system"}}) {
            try { c.set(pair[0], pair[1]); fail("Accepted invalid config"); } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void usSpringAndFallUseDifferentOffsets() throws Exception {
        int offset = DstRules.baseOffsetMinutes("Mobis/UTC-05:00"); assertEquals(-300, offset);
        assertFalse(DstRules.active(utc("2026-03-08 06:59"), offset, "us"));
        assertTrue(DstRules.active(utc("2026-03-08 07:00"), offset, "us"));
        assertTrue(DstRules.active(utc("2026-11-01 05:59"), offset, "us"));
        assertFalse(DstRules.active(utc("2026-11-01 06:00"), offset, "us"));
        assertEquals(utc("2027-03-14 07:00"), DstRules.next(utc("2026-11-01 06:00"), offset, "us"));
    }
    @Test public void europeUsesUtcAndNoDstNeverSchedules() throws Exception {
        assertFalse(DstRules.active(utc("2026-03-29 00:59"), 330, "eu"));
        assertTrue(DstRules.active(utc("2026-03-29 01:00"), 330, "eu"));
        assertFalse(DstRules.active(utc("2026-10-25 01:00"), -300, "eu"));
        assertFalse(DstRules.active(utc("2026-07-01 12:00"), -300, "none"));
        assertEquals(Long.MAX_VALUE, DstRules.next(0, 0, "none"));
        assertEquals(345, DstRules.baseOffsetMinutes("Mobis/UTC+05:45"));
    }
    @Test public void rejectsUnknownZoneInsteadOfGuessing() {
        for (String zone : new String[]{"America/New_York", "Mobis/UTC+99:00", "Mobis/UTC-05:99", "Mobis/UTC+14:30"}) {
            try { DstRules.baseOffsetMinutes(zone); fail("Accepted bad zone"); } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void temperatureRejectsUninitializedAndUnavailableSignalsAndKeepsPrecision() {
        for (String signal : new String[]{"00", "ff", "FF", "ffff"})
            assertNull(HeaterPolicy.outsideC("  AmbientTempF : 0x" + signal + "\n  mAmbientTempF: 12.5\n"));
        assertNull(HeaterPolicy.outsideC("mAmbientTempF: 12.5\n"));
        assertEquals(0.0, HeaterPolicy.outsideC("AmbientTempF : 0x52\nmAmbientTempF: 32.0\n"), 0.00001);
        assertEquals(-1.5, HeaterPolicy.outsideC("AmbientTempC : 0x52\nmAmbientTempC: -1.5\n"), 0.00001);
        assertNull(HeaterPolicy.outsideC("AmbientTempF : 0x52\nmAmbientTempF: 999\n"));
        assertTrue(HeaterPolicy.cold(9.99, 100)); assertFalse(HeaterPolicy.cold(10.0, 100));
        assertFalse(HeaterPolicy.cold(10.01, 100));
    }
    @Test public void seatLevelsMapToActuatorEnums() { assertEquals(6, HeaterPolicy.seatRequest(1)); assertEquals(7, HeaterPolicy.seatRequest(2)); assertEquals(8, HeaterPolicy.seatRequest(3)); }
    @Test public void vehicleAndDstChecksAreLimitedToTheirOwnChanges() {
        Config before = new Config(new java.util.Properties()); before.set("dst", "true"); before.set("tracks", "true"); before.set("heaters", "true");
        Config next = new Config((java.util.Properties) before.values.clone()); next.set("nav", "true");
        assertFalse(next.needsVehicleCheck(before)); assertFalse(next.dstChanged(before)); assertTrue(next.staticChanged(before));
        next.set("threshold", "106"); assertFalse(next.needsVehicleCheck(before)); assertFalse(next.dstChanged(before));
        next.set("region", "eu"); assertTrue(next.needsVehicleCheck(before)); assertTrue(next.dstChanged(before));
        next.set("dst", "false"); assertFalse(next.needsVehicleCheck(before));
        next.set("heaters", "false"); assertFalse(next.needsVehicleCheck(before));
    }
    @Test public void explicitDefaultsAreSemanticallyUnchanged() {
        Config before = new Config(new java.util.Properties()), next = new Config(new java.util.Properties());
        next.set("threshold", "100"); next.set("region", "us"); next.set("driver", "true"); next.set("nav", "false");
        assertTrue(next.sameSettings(before));
    }

}
