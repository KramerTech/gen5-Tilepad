package com.tilepad;

import org.junit.Test;
import static org.junit.Assert.*;

public class Ioniq5NeevCa260206CompatibilityTest {
    private static boolean ca(int sdk, String device, String software, String framework, String service) {
        return PreconditioningProfile.matchesFirmware(sdk, device, software, framework, service);
    }
    @Test public void exactCollectedIoniq5NeevCa260206FirmwareMatches() {
        assertTrue(PreconditioningProfile.supportsDevice(19, "wp_daudioplus_neev_ca"));
        assertTrue(ca(19, "wp_daudioplus_neev_ca", "NEEV.CAN.0000.V015.011.260206",
            "301b134d231b1ca1d0aa75ae090d219ac963fc935c3f3bc0b3b1393c6614703e",
            "efb949ec92fff388cbbb78e5bf4e16df3cc8d417dbf5cb5e87e62fb46d542549"));
    }
    @Test public void mismatchedApiDeviceSoftwareOrEitherHashCannotEnableIoniq5NeevCa260206() {
        String device = PreconditioningProfile.IONIQ5_NEEV_CA_DEVICE, software = PreconditioningProfile.IONIQ5_NEEV_CA_260206_SOFTWARE;
        String framework = PreconditioningProfile.IONIQ5_NEEV23_US_260206_FRAMEWORK_SHA256,
            service = PreconditioningProfile.IONIQ5_NEEV23_US_260206_SERVICE_SHA256;
        assertFalse(ca(20, device, software, framework, service));
        assertFalse(ca(19, "wp_daudioplus_neev_us", software, framework, service));
        assertFalse(ca(19, PreconditioningProfile.IONIQ5_NEEV23_US_DEVICE, software, framework, service));
        assertFalse(ca(19, device, "NEEV.CAN.0000.V015.011.999999", framework, service));
        assertFalse(ca(19, device, PreconditioningProfile.IONIQ5_NEEV23_US_260206_SOFTWARE, framework, service));
        assertFalse(ca(19, device, "", framework, service));
        assertFalse(ca(19, device, null, framework, service));
        assertFalse(ca(19, device, software, "unknown", service));
        assertFalse(ca(19, device, software, framework, "unknown"));
        assertFalse(ca(19, device, software, null, null));
        // The 260507 files aren't this image's, and the pair only counts together.
        assertFalse(ca(19, device, software, PreconditioningProfile.IONIQ5_NEEV23_US_FRAMEWORK_SHA256,
            PreconditioningProfile.IONIQ5_NEEV23_US_SERVICE_SHA256));
        assertFalse(ca(19, device, software, framework, PreconditioningProfile.IONIQ5_NEEV23_US_SERVICE_SHA256));
        assertFalse(ca(19, device, software, PreconditioningProfile.IONIQ5_NEEV23_US_FRAMEWORK_SHA256, service));
    }
    @Test public void ioniq5NeevCa260206DoesNotChangeTheOtherProfiles() {
        // The Canada software doesn't unlock the USA device, and the USA 260206 profile still matches.
        assertFalse(ca(19, PreconditioningProfile.IONIQ5_NEEV23_US_DEVICE, PreconditioningProfile.IONIQ5_NEEV_CA_260206_SOFTWARE,
            PreconditioningProfile.IONIQ5_NEEV23_US_260206_FRAMEWORK_SHA256, PreconditioningProfile.IONIQ5_NEEV23_US_260206_SERVICE_SHA256));
        assertTrue(ca(19, PreconditioningProfile.IONIQ5_NEEV23_US_DEVICE, PreconditioningProfile.IONIQ5_NEEV23_US_260206_SOFTWARE,
            PreconditioningProfile.IONIQ5_NEEV23_US_260206_FRAMEWORK_SHA256, PreconditioningProfile.IONIQ5_NEEV23_US_260206_SERVICE_SHA256));
        assertTrue(ca(19, PreconditioningProfile.EV6_DEVICE, PreconditioningProfile.EV6_SOFTWARE,
            PreconditioningProfile.EV6_FRAMEWORK_SHA256, PreconditioningProfile.EV6_SERVICE_SHA256));
    }
}
