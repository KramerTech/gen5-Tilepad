package com.tilepad;

import org.junit.Test;
import static org.junit.Assert.*;

public class InspectedCodeTest {
    // The Ioniq 5 V016 pair's code hashes, which the Ioniq 6 V016 files share.
    private static final String V016_AUTOMOTIVE = "df3d65166acc742dc8b342d3c3018c267326d481b6ecc4c6736d62414729490a",
        V016_SERVICE = "ccaeef289a9e8902e547df25d996d8d151ad17bf8cdeea9c0da96ec99421fa6f",
        EV6_AUTOMOTIVE = "310d2c9b7e1b186ceee08cae38b4720202e9c6e09f2e4a23d4642b6894938dec",
        EV6_SERVICE = "8b6324dc3456327e608811b1387a196fe53ed600af9223c620a0eb001e0f7726";
    @Test public void aSupportedDeviceRunningInspectedCodeMatches() {
        assertTrue(PreconditioningProfile.matchesInspectedCode(19, "wp_daudioplus_ceev_us", V016_AUTOMOTIVE, V016_SERVICE));
        assertTrue(PreconditioningProfile.matchesInspectedCode(19, "wp_daudioplus_neev_us", V016_AUTOMOTIVE, V016_SERVICE));
        assertTrue(PreconditioningProfile.matchesInspectedCode(19, "wp_daudioplus_cvev_ca", EV6_AUTOMOTIVE, EV6_SERVICE));
    }
    @Test public void onlyAWholePairOnASupportedDeviceCounts() {
        assertFalse(PreconditioningProfile.matchesInspectedCode(20, "wp_daudioplus_ceev_us", V016_AUTOMOTIVE, V016_SERVICE));
        // Other models aren't supported, whatever their code.
        assertFalse(PreconditioningProfile.matchesInspectedCode(19, "wp_daudioplus_aepeev_us", V016_AUTOMOTIVE, V016_SERVICE));
        assertFalse(PreconditioningProfile.matchesInspectedCode(19, "wp_daudioplus_ceev_us", V016_AUTOMOTIVE, EV6_SERVICE));
        assertFalse(PreconditioningProfile.matchesInspectedCode(19, "wp_daudioplus_ceev_us", V016_SERVICE, V016_AUTOMOTIVE));
        assertFalse(PreconditioningProfile.matchesInspectedCode(19, "wp_daudioplus_ceev_us", null, V016_SERVICE));
        assertFalse(PreconditioningProfile.matchesInspectedCode(19, "wp_daudioplus_ceev_us", V016_AUTOMOTIVE, null));
        // Raw file hashes aren't code hashes.
        assertFalse(PreconditioningProfile.matchesInspectedCode(19, "wp_daudioplus_ceev_us",
            PreconditioningProfile.IONIQ5_NEEV23_US_260903_FRAMEWORK_SHA256, PreconditioningProfile.IONIQ5_NEEV23_US_260903_SERVICE_SHA256));
    }
}
