package com.tilepad.tweakcore;

import org.junit.Test;
import java.text.SimpleDateFormat;
import java.util.*;
import static org.junit.Assert.*;

public class PolicyTest {
    private static long utc(String date) throws Exception { SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US); f.setTimeZone(TimeZone.getTimeZone("UTC")); return f.parse(date).getTime(); }
    @Test public void everyTweakDefaultsOffAndOptionsAreValidated() {
        Config c = new Config(new Properties()); for (String id : Config.IDS) assertFalse(c.enabled(id));
        assertEquals(100, c.number("threshold")); assertEquals(270, c.number("coolLow"));
        assertEquals(300, c.number("coolMed")); assertEquals(320, c.number("coolHigh"));
        assertFalse(c.checked("cooling")); assertEquals("us", c.get("region"));
        for (String[] pair : new String[][]{{"threshold", "601"}, {"coolLow", "-401"}, {"coolMed", "601"}, {"coolHigh", "601"}, {"cooling", "1"}, {"driverLevel", "0"}, {"region", "$(reboot)"}, {"dst", "1"}, {"path", "/system"}}) {
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
        // A US car's dump: the °C channel stays unavailable and °F carries the reading.
        String dump = "  mAmbientTempC: -40.0\n  mAmbientTempF: 56.0\n  AmbientTempC : 0xff\n  AmbientTempF : 0x60\n";
        assertEquals(13.3333, HeaterPolicy.outsideC(dump), 0.0001);
        assertEquals("°F signal 0x60, shown as 56.0 and °C signal 0xff, shown as -40.0", HeaterPolicy.reported(dump));
        assertEquals("°F signal missing, shown as nothing and °C signal missing, shown as nothing", HeaterPolicy.reported(""));
        assertTrue(HeaterPolicy.cold(9.99, 100)); assertTrue(HeaterPolicy.cold(10.0, 100));
        assertFalse(HeaterPolicy.cold(10.01, 100));
    }
    @Test public void seatLevelsMapToActuatorEnums() {
        assertEquals(6, HeaterPolicy.seatRequest(1)); assertEquals(7, HeaterPolicy.seatRequest(2)); assertEquals(8, HeaterPolicy.seatRequest(3));
        assertEquals(3, HeaterPolicy.coolRequest(1)); assertEquals(4, HeaterPolicy.coolRequest(2)); assertEquals(5, HeaterPolicy.coolRequest(3));
        try { HeaterPolicy.coolRequest(0); fail(); } catch (IllegalArgumentException expected) { }
        try { HeaterPolicy.coolRequest(4); fail(); } catch (IllegalArgumentException expected) { }
    }
    @Test public void coolingLevelsAndPerSeatEquipmentCheckFollowThresholdsAndOptionCode() {
        assertEquals(0, HeaterPolicy.coolingLevel(26.99, 270, 300, 320));
        assertEquals(1, HeaterPolicy.coolingLevel(27.0, 270, 300, 320));
        assertEquals(1, HeaterPolicy.coolingLevel(29.99, 270, 300, 320));
        assertEquals(2, HeaterPolicy.coolingLevel(30.0, 270, 300, 320));
        assertEquals(2, HeaterPolicy.coolingLevel(31.99, 270, 300, 320));
        assertEquals(3, HeaterPolicy.coolingLevel(32.0, 270, 300, 320));
        assertEquals(3, HeaterPolicy.coolingLevel(30.0, 300, 300, 300));
        assertFalse(HeaterPolicy.isFitted("driver", 0)); assertFalse(HeaterPolicy.isCoolingFitted("driver", 0));
        assertTrue(HeaterPolicy.isFitted("driver", 2)); assertFalse(HeaterPolicy.isCoolingFitted("driver", 2));
        assertTrue(HeaterPolicy.isFitted("driver", 6)); assertTrue(HeaterPolicy.isCoolingFitted("driver", 6));
        assertTrue(HeaterPolicy.isFitted("passenger", 6)); assertTrue(HeaterPolicy.isCoolingFitted("passenger", 6));
        assertTrue(HeaterPolicy.isFitted("wheel", 6)); assertFalse(HeaterPolicy.isCoolingFitted("wheel", 6));
        HeaterPolicy.PassengerWait heat = HeaterPolicy.parsePassengerWait("boot-1 A outcome");
        assertEquals("boot-1", heat.boot); assertEquals(0, heat.coolLevel); assertEquals("A outcome", heat.outcome);
        HeaterPolicy.PassengerWait cool = HeaterPolicy.parsePassengerWait(HeaterPolicy.passengerWaitRecord("boot-2", 2, "Driver seat cooling: ok. "));
        assertEquals("boot-2", cool.boot); assertEquals(2, cool.coolLevel); assertEquals("Driver seat cooling: ok. ", cool.outcome);
    }
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
        next.set("threshold", "100"); next.set("cooling", "false"); next.set("coolLow", "270"); next.set("coolMed", "300"); next.set("coolHigh", "320");
        next.set("region", "us"); next.set("driver", "true"); next.set("nav", "false");
        assertTrue(next.sameSettings(before));
    }

}
