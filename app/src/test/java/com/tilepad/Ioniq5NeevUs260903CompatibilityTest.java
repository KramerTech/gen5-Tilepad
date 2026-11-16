package com.tilepad;

import org.junit.Test;
import static org.junit.Assert.*;

public class Ioniq5NeevUs260903CompatibilityTest {
    private static boolean neev(int sdk, String device, String software, String framework, String service) {
        return PreconditioningProfile.matchesFirmware(sdk, device, software, framework, service);
    }
    @Test public void neevUs260903MatchesWithNeev23Us260903sFiles() {
        assertTrue(neev(19, "wp_daudioplus_neev_us", "NEEV.USA.0000.V016.010.260903",
            "c52e0825956880d627b32979022705a618200f059476c094d729133d1ff6a3be",
            "bf17e682d1beb349ae8ef2acaae6d6e908fe42e88853d7145ee16b29bb4d9ec1"));
        // An Ioniq 5: two minutes and 21°C.
        assertEquals(2 * 60 * 1000L, PreconditioningProfile.activationTimeoutMs("wp_daudioplus_neev_us"));
        assertEquals(21, PreconditioningProfile.warmThresholdC("wp_daudioplus_neev_us"));
    }
    @Test public void anyOtherApiDeviceSoftwareOrHashIsRefused() {
        String device = PreconditioningProfile.IONIQ5_NEEV_US_DEVICE, software = PreconditioningProfile.IONIQ5_NEEV_US_260903_SOFTWARE;
        String framework = PreconditioningProfile.IONIQ5_NEEV23_US_260903_FRAMEWORK_SHA256,
            service = PreconditioningProfile.IONIQ5_NEEV23_US_260903_SERVICE_SHA256;
        assertFalse(neev(20, device, software, framework, service));
        assertFalse(neev(19, PreconditioningProfile.IONIQ5_NEEV_CA_DEVICE, software, framework, service));
        assertFalse(neev(19, PreconditioningProfile.EV6_DEVICE, software, framework, service));
        assertFalse(neev(19, device, "NEEV.USA.0000.V016.010.999999", framework, service));
        assertFalse(neev(19, device, null, framework, service));
        assertFalse(neev(19, device, software, "unknown", service));
        assertFalse(neev(19, device, software, framework, null));
        // The 260903 software doesn't unlock the NEEV US 260507 or 240822 files, and vice versa.
        assertFalse(neev(19, device, software, PreconditioningProfile.IONIQ5_NEEV23_US_FRAMEWORK_SHA256,
            PreconditioningProfile.IONIQ5_NEEV23_US_SERVICE_SHA256));
        assertFalse(neev(19, device, software, PreconditioningProfile.IONIQ5_NEEV_US_240822_FRAMEWORK_SHA256,
            PreconditioningProfile.IONIQ5_NEEV_US_240822_SERVICE_SHA256));
        assertFalse(neev(19, device, PreconditioningProfile.IONIQ5_NEEV_US_SOFTWARE, framework, service));
        assertFalse(neev(19, device, software, framework, PreconditioningProfile.IONIQ5_NEEV23_US_SERVICE_SHA256));
    }
    @Test public void theOtherNeevUsAndNeev23UsReleasesStillMatch() {
        assertTrue(neev(19, PreconditioningProfile.IONIQ5_NEEV_US_DEVICE, PreconditioningProfile.IONIQ5_NEEV_US_SOFTWARE,
            PreconditioningProfile.IONIQ5_NEEV23_US_FRAMEWORK_SHA256, PreconditioningProfile.IONIQ5_NEEV23_US_SERVICE_SHA256));
        assertTrue(neev(19, PreconditioningProfile.IONIQ5_NEEV_US_DEVICE, PreconditioningProfile.IONIQ5_NEEV_US_240822_SOFTWARE,
            PreconditioningProfile.IONIQ5_NEEV_US_240822_FRAMEWORK_SHA256, PreconditioningProfile.IONIQ5_NEEV_US_240822_SERVICE_SHA256));
        assertTrue(neev(19, PreconditioningProfile.IONIQ5_NEEV23_US_DEVICE, PreconditioningProfile.IONIQ5_NEEV23_US_260903_SOFTWARE,
            PreconditioningProfile.IONIQ5_NEEV23_US_260903_FRAMEWORK_SHA256, PreconditioningProfile.IONIQ5_NEEV23_US_260903_SERVICE_SHA256));
    }
}
