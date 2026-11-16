package com.tilepad;

import org.junit.Test;
import static org.junit.Assert.*;

public class Ev6241120CompatibilityTest {
    private static boolean ev6(int sdk, String device, String software, String framework, String service) {
        return PreconditioningProfile.matchesFirmware(sdk, device, software, framework, service);
    }
    @Test public void exactEv6241120FirmwareMatches() {
        assertTrue(ev6(19, "wp_daudioplus_cvev_us", "CVEV.USA.0000.V013.010.241120",
            "a56e6f3ae6918178cb2761169d123db4d973afc9f72598ae05b05f77f08e6761",
            "94ed2da330916f41e9b751731b4abb7564711a77ae18c565807a97a0711e8c32"));
        // Same device as the other US EV6 releases: their five minutes and 20°C.
        assertEquals(5 * 60 * 1000L, PreconditioningProfile.activationTimeoutMs("wp_daudioplus_cvev_us"));
        assertEquals(20, PreconditioningProfile.warmThresholdC("wp_daudioplus_cvev_us"));
    }
    @Test public void anyOtherApiDeviceSoftwareOrHashIsRefused() {
        String device = PreconditioningProfile.EV6_DEVICE, software = PreconditioningProfile.EV6_241120_SOFTWARE;
        String framework = PreconditioningProfile.EV6_241120_FRAMEWORK_SHA256, service = PreconditioningProfile.EV6_241120_SERVICE_SHA256;
        assertFalse(ev6(20, device, software, framework, service));
        assertFalse(ev6(19, PreconditioningProfile.EV6_CVEV_KR_DEVICE, software, framework, service));
        assertFalse(ev6(19, PreconditioningProfile.IONIQ5_NEEV_US_DEVICE, software, framework, service));
        assertFalse(ev6(19, null, software, framework, service));
        assertFalse(ev6(19, device, "CVEV.USA.0000.V013.010.999999", framework, service));
        assertFalse(ev6(19, device, "CVEV.USA.S5W_M.V013.010.241120", framework, service));
        assertFalse(ev6(19, device, null, framework, service));
        assertFalse(ev6(19, device, software, "unknown", service));
        assertFalse(ev6(19, device, software, framework, null));
        // The pair only counts together, and never with another EV6 release's files or software.
        assertFalse(ev6(19, device, software, framework, PreconditioningProfile.EV6_251120_SERVICE_SHA256));
        assertFalse(ev6(19, device, software, PreconditioningProfile.EV6_FRAMEWORK_SHA256, service));
        assertFalse(ev6(19, device, software, PreconditioningProfile.EV6_251120_FRAMEWORK_SHA256, PreconditioningProfile.EV6_251120_SERVICE_SHA256));
        assertFalse(ev6(19, device, PreconditioningProfile.EV6_251120_SOFTWARE, framework, service));
        assertFalse(ev6(19, device, PreconditioningProfile.EV6_SOFTWARE, framework, service));
    }
    @Test public void theOtherEv6ReleasesStillMatch() {
        assertTrue(ev6(19, PreconditioningProfile.EV6_DEVICE, PreconditioningProfile.EV6_SOFTWARE,
            PreconditioningProfile.EV6_FRAMEWORK_SHA256, PreconditioningProfile.EV6_SERVICE_SHA256));
        assertTrue(ev6(19, PreconditioningProfile.EV6_DEVICE, PreconditioningProfile.EV6_251120_SOFTWARE,
            PreconditioningProfile.EV6_251120_FRAMEWORK_SHA256, PreconditioningProfile.EV6_251120_SERVICE_SHA256));
    }
}
