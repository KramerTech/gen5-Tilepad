package com.tilepad;

import org.junit.Test;
import static org.junit.Assert.*;

public class Ioniq6CeevUs260903CompatibilityTest {
    private static final String DEVICE = PreconditioningProfile.IONIQ6_CEEV_US_DEVICE,
        SOFTWARE = PreconditioningProfile.IONIQ6_CEEV_US_260903_SOFTWARE,
        FRAMEWORK = PreconditioningProfile.IONIQ6_CEEV_US_260903_FRAMEWORK_SHA256,
        SERVICE = PreconditioningProfile.IONIQ6_CEEV_US_260903_SERVICE_SHA256;
    @Test public void exactCollectedIoniq6CeevUs260903FirmwareMatches() {
        assertTrue(PreconditioningProfile.matchesFirmware(19, "wp_daudioplus_ceev_us", "CEEV.USA.0000.V016.010.260903",
            "c2d3ae2375ed96f951f4eb4f29f15424d58779c18148a6ab08e491703ea96f2e",
            "c06c9dc292371a7614ff037755b8e62d9fe4afa05141f1f6688f9c37bc722664"));
        // The Ioniq 6's own timeout and threshold.
        assertEquals(2 * 60 * 1000L, PreconditioningProfile.activationTimeoutMs(DEVICE));
        assertEquals(20, PreconditioningProfile.warmThresholdC(DEVICE));
    }
    @Test public void anyOtherApiDeviceSoftwareOrHashIsRefused() {
        assertFalse(PreconditioningProfile.matchesFirmware(20, DEVICE, SOFTWARE, FRAMEWORK, SERVICE));
        assertFalse(PreconditioningProfile.matchesFirmware(19, PreconditioningProfile.IONIQ5_NEEV23_US_DEVICE, SOFTWARE, FRAMEWORK, SERVICE));
        assertFalse(PreconditioningProfile.matchesFirmware(19, DEVICE, PreconditioningProfile.IONIQ6_CEEV_US_SOFTWARE, FRAMEWORK, SERVICE));
        assertFalse(PreconditioningProfile.matchesFirmware(19, DEVICE, SOFTWARE,
            PreconditioningProfile.IONIQ6_CEEV_US_FRAMEWORK_SHA256, PreconditioningProfile.IONIQ6_CEEV_US_SERVICE_SHA256));
        // The Ioniq 5 V016 files it was compared with aren't its own.
        assertFalse(PreconditioningProfile.matchesFirmware(19, DEVICE, SOFTWARE,
            PreconditioningProfile.IONIQ5_NEEV23_US_260903_FRAMEWORK_SHA256, PreconditioningProfile.IONIQ5_NEEV23_US_260903_SERVICE_SHA256));
        assertFalse(PreconditioningProfile.matchesFirmware(19, DEVICE, SOFTWARE, FRAMEWORK, PreconditioningProfile.IONIQ6_CEEV_US_SERVICE_SHA256));
        assertFalse(PreconditioningProfile.matchesFirmware(19, DEVICE, null, FRAMEWORK, SERVICE));
    }
}
