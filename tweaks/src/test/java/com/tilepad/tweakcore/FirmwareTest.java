package com.tilepad.tweakcore;

import static org.junit.Assert.*;
import org.junit.Test;

public class FirmwareTest {
    private static final Firmware.Release V015 = Firmware.SUPPORTED[0], V016 = Firmware.SUPPORTED[1], KIA_V015 = Firmware.SUPPORTED[2],
        V012 = Firmware.SUPPORTED[3];
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
        assertFalse(Firmware.evSettings("0000"));
    }
    @Test public void heaterCodesComeFromTheMatchedRelease() {
        // V012's IUSMService numbers each heater method lower: V015's driver code is V012's passenger seat.
        Firmware.Usm v012 = Firmware.vehicleRelease(V012.automotive, V012.automotiveService).usm;
        Firmware.Usm v015 = Firmware.vehicleRelease(V015.automotive, V015.automotiveService).usm;
        assertEquals(0xd4, v012.driverHeat); assertEquals(0xd5, v012.passengerHeat);
        assertEquals(0xd5, v015.driverHeat); assertEquals(0xd6, v015.passengerHeat);
        assertEquals(0x23f, HeaterPolicy.fittedTransaction(v012, "wheel"));
        assertEquals(0x241, HeaterPolicy.fittedTransaction(v015, "wheel"));
        assertNull(Firmware.vehicleRelease(V012.automotive, V015.automotiveService));
    }
}
