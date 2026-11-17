package com.tilepad.tweakcore;

import static org.junit.Assert.*;
import org.junit.Test;

public class FirmwareTest {
    private static final Firmware.Release V015 = Firmware.SUPPORTED[0], V016 = Firmware.SUPPORTED[1], KIA_V015 = Firmware.SUPPORTED[2],
        V012 = Firmware.SUPPORTED[3], V015_CA = Firmware.SUPPORTED[4], KIA_V015_CA = Firmware.SUPPORTED[5],
        DN8 = Firmware.SUPPORTED[6], KIA_BASE = Firmware.SUPPORTED[7], CE = Firmware.SUPPORTED[8],
        CE_V016 = Firmware.SUPPORTED[9], V014 = Firmware.SUPPORTED[10];
    @Test public void acceptsEachSupportedReleasesFiles() {
        for (Firmware.Release r : Firmware.SUPPORTED) {
            assertTrue(r.name, Firmware.vehicle(r.automotive, r.automotiveService));
            assertTrue(r.name, Firmware.projection(r.framework, r.framework2));
            assertTrue(r.name, Firmware.evSettings(r.evSettings));
        }
    }
    @Test public void aPairMustComeFromOneRelease() {
        assertFalse(Firmware.vehicle(V015.automotive, V016.automotiveService));
        assertFalse(Firmware.projection(V016.framework, V015.framework2));
        // Hyundai's and Kia's images of one release are separate builds: no mixing either.
        assertFalse(Firmware.vehicle(V015.automotive, KIA_V015.automotiveService));
        assertFalse(Firmware.projection(KIA_V015.framework, V015.framework2));
        assertFalse(Firmware.vehicle(V012.automotive, V015.automotiveService));
        assertFalse(Firmware.projection(V015.framework, V012.framework2));
        // V015.011 is its own build: its odex files differ from V015.012's, though the code is the same.
        assertFalse(Firmware.vehicle(V015_CA.automotive, V015.automotiveService));
        assertFalse(Firmware.projection(V015.framework, V015_CA.framework2));
        // Kia's V015.011 deodexes identically to V015.012, but its ODEX files are its own build: no mixing.
        assertFalse(Firmware.vehicle(KIA_V015_CA.automotive, KIA_V015.automotiveService));
        assertFalse(Firmware.projection(KIA_V015.framework, KIA_V015_CA.framework2));
        // Hyundai's EV and non-EV images of one release are separate builds too.
        assertFalse(Firmware.vehicle(V015.automotive, DN8.automotiveService));
        assertFalse(Firmware.projection(DN8.framework, V015.framework2));
        // KIA.USA (Niro EV/Sorento) and ECOKMC (EV6) are separate images of V015.
        assertFalse(Firmware.vehicle(KIA_BASE.automotive, KIA_V015.automotiveService));
        assertFalse(Firmware.vehicle(KIA_V015.automotive, KIA_BASE.automotiveService));
        assertFalse(Firmware.projection(KIA_BASE.framework, KIA_V015.framework2));
        assertFalse(Firmware.projection(KIA_V015.framework, KIA_BASE.framework2));
        assertFalse(Firmware.vehicle(KIA_BASE.automotive, DN8.automotiveService));
        assertFalse(Firmware.projection(DN8.framework, KIA_BASE.framework2));
        // The IONIQ 6's ECOHMCSE image is the IONIQ 5's code, but its own build of every file.
        assertFalse(Firmware.vehicle(CE.automotive, V015.automotiveService));
        assertFalse(Firmware.vehicle(V015.automotive, CE.automotiveService));
        assertFalse(Firmware.projection(CE.framework, V015.framework2));
        assertFalse(Firmware.projection(V015.framework, CE.framework2));
        assertFalse(Firmware.vehicle(CE_V016.automotive, V016.automotiveService));
        assertFalse(Firmware.vehicle(CE.automotive, CE_V016.automotiveService));
        assertFalse(Firmware.projection(V016.framework, CE_V016.framework2));
        assertFalse(Firmware.projection(CE_V016.framework, CE.framework2));
        assertFalse(Firmware.vehicle(V014.automotive, V012.automotiveService));
        assertFalse(Firmware.vehicle(V015.automotive, V014.automotiveService));
        assertFalse(Firmware.projection(V014.framework, V015.framework2));
        assertFalse(Firmware.projection(V012.framework, V014.framework2));
        assertFalse(Firmware.evSettings("0000"));
    }
    @Test public void starHoldCodesComeFromTheMatchedRelease() {
        // IModeManager getKeyAction, disableKeyAction, enableKeyAction: one lower on V012 only (V014 has V015's).
        for (Firmware.Release r : Firmware.SUPPORTED) {
            boolean v012 = r == V012;
            assertEquals(r.name, v012 ? 0x2a : 0x2b, r.mode.keyAction);
            assertEquals(r.name, v012 ? 0x2b : 0x2c, r.mode.disableKeyAction);
            assertEquals(r.name, v012 ? 0x2c : 0x2d, r.mode.enableKeyAction);
        }
    }
    @Test public void heaterCodesComeFromTheMatchedRelease() {
        // V012's IUSMService numbers each heater method lower: V015's driver code is V012's passenger seat.
        Firmware.Usm v012 = Firmware.vehicleRelease(V012.automotive, V012.automotiveService).usm;
        Firmware.Usm v015 = Firmware.vehicleRelease(V015.automotive, V015.automotiveService).usm;
        assertEquals(0xd4, v012.driverHeat); assertEquals(0xd5, v012.passengerHeat);
        assertEquals(0xd5, v015.driverHeat); assertEquals(0xd6, v015.passengerHeat);
        assertEquals(0x23f, HeaterPolicy.fittedTransaction(v012, "wheel"));
        assertEquals(0x241, HeaterPolicy.fittedTransaction(v015, "wheel"));
        assertSame(v012, Firmware.vehicleRelease(V014.automotive, V014.automotiveService).usm);
        assertNull(Firmware.vehicleRelease(V012.automotive, V015.automotiveService));
    }
}
