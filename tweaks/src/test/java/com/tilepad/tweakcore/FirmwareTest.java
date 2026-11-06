package com.tilepad.tweakcore;

import static org.junit.Assert.*;
import org.junit.Test;

public class FirmwareTest {
    private static final Firmware.Release V015 = Firmware.SUPPORTED[0], V016 = Firmware.SUPPORTED[1];
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
        assertFalse(Firmware.evSettings("0000"));
    }
}
