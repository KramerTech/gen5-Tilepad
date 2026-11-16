package com.tilepad;

import org.junit.Test;
import static org.junit.Assert.*;

public class Ev6CvevCa260206CompatibilityTest {
    private static boolean ca(int sdk, String device, String software, String framework, String service) {
        return PreconditioningProfile.matchesFirmware(sdk, device, software, framework, service);
    }
    @Test public void exactCollectedEv6CvevCa260206FirmwareMatches() {
        assertTrue(PreconditioningProfile.supportsDevice(19, "wp_daudioplus_cvev_ca"));
        assertTrue(ca(19, "wp_daudioplus_cvev_ca", "CVEV.CAN.0000.V015.011.260206",
            "347d05034e6b25e38f8d0446a6e9f02f4c8f5b786c09e583a500235613514c18",
            "80bc04d04822464cfedb8878592f04eab8b16351be7aef6c70ff1ec9aa14d021"));
        // The US EV6's code, so the US EV6's five minutes and 20°C, not a new port's defaults.
        assertEquals(5 * 60 * 1000L, PreconditioningProfile.activationTimeoutMs("wp_daudioplus_cvev_ca"));
        assertEquals(20, PreconditioningProfile.warmThresholdC("wp_daudioplus_cvev_ca"));
    }
    @Test public void anyOtherApiDeviceSoftwareOrHashIsRefused() {
        String device = PreconditioningProfile.EV6_CVEV_CA_DEVICE, software = PreconditioningProfile.EV6_CVEV_CA_260206_SOFTWARE;
        String framework = PreconditioningProfile.EV6_CVEV_CA_260206_FRAMEWORK_SHA256,
            service = PreconditioningProfile.EV6_CVEV_CA_260206_SERVICE_SHA256;
        assertFalse(ca(20, device, software, framework, service));
        assertFalse(ca(19, PreconditioningProfile.EV6_DEVICE, software, framework, service));
        assertFalse(ca(19, PreconditioningProfile.IONIQ5_NEEV_CA_DEVICE, software, framework, service));
        assertFalse(ca(19, device, "CVEV.CAN.0000.V015.011.999999", framework, service));
        assertFalse(ca(19, device, PreconditioningProfile.EV6_SOFTWARE, framework, service));
        assertFalse(ca(19, device, null, framework, service));
        assertFalse(ca(19, device, software, "unknown", service));
        assertFalse(ca(19, device, software, framework, null));
        // Not the US EV6's files, though the code is the same, and the pair only counts together.
        assertFalse(ca(19, device, software, PreconditioningProfile.EV6_FRAMEWORK_SHA256, PreconditioningProfile.EV6_SERVICE_SHA256));
        assertFalse(ca(19, device, software, framework, PreconditioningProfile.EV6_SERVICE_SHA256));
        assertFalse(ca(19, device, software, PreconditioningProfile.EV6_FRAMEWORK_SHA256, service));
    }
    @Test public void theUsEv6AndCanadianIoniq5StillMatchOnlyTheirOwn() {
        assertTrue(ca(19, PreconditioningProfile.EV6_DEVICE, PreconditioningProfile.EV6_SOFTWARE,
            PreconditioningProfile.EV6_FRAMEWORK_SHA256, PreconditioningProfile.EV6_SERVICE_SHA256));
        assertFalse(ca(19, PreconditioningProfile.EV6_DEVICE, PreconditioningProfile.EV6_SOFTWARE,
            PreconditioningProfile.EV6_CVEV_CA_260206_FRAMEWORK_SHA256, PreconditioningProfile.EV6_CVEV_CA_260206_SERVICE_SHA256));
        assertTrue(ca(19, PreconditioningProfile.IONIQ5_NEEV_CA_DEVICE, PreconditioningProfile.IONIQ5_NEEV_CA_260206_SOFTWARE,
            PreconditioningProfile.IONIQ5_NEEV23_US_260206_FRAMEWORK_SHA256, PreconditioningProfile.IONIQ5_NEEV23_US_260206_SERVICE_SHA256));
        // The Korean EV6 keeps a new port's two minutes and 21°C.
        assertEquals(2 * 60 * 1000L, PreconditioningProfile.activationTimeoutMs(PreconditioningProfile.EV6_CVEV_KR_DEVICE));
    }
}
