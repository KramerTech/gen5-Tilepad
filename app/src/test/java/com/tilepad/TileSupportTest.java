package com.tilepad;

import org.junit.Test;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

public class TileSupportTest {
    private static Set<String> features(String... names) { return new HashSet<>(Arrays.asList(names)); }

    @Test public void ioniq5ShowsEverything() {
        TileSupport s = TileSupport.of(features(TileSupport.ANDROID_WIFI, TileSupport.EV,
            TileSupport.WIFI_MODULE, "com.hkmc.software.climate"), false);
        for (TileSpec.Kind kind : TileSpec.CORE) assertTrue(kind.name(), s.shows(kind));
        assertTrue(s.wifiClient);
    }

    @Test public void hotspotOnlyUnitShowsWifiWithClientOff() {
        // Stock US firmware, or wireless projection installed without its Wi-Fi client option.
        TileSupport s = TileSupport.of(features(TileSupport.WIFI_MODULE, TileSupport.EV,
            "com.hkmc.software.wifihotspot"), false);
        assertTrue(s.shows(TileSpec.Kind.WIFI));
        assertFalse(s.wifiClient);
    }

    @Test public void gasCarHidesPreconditioning() {
        TileSupport s = TileSupport.of(features(TileSupport.WIFI_MODULE, "com.hkmc.software.climate"), false);
        assertFalse(s.shows(TileSpec.Kind.PRECONDITION));
        assertTrue(s.shows(TileSpec.Kind.WIFI));
        assertTrue(s.shows(TileSpec.Kind.REBOOT));
    }

    @Test public void hyundaiWithoutWifiModuleIgnoresAndroidWifiFlag() {
        // A NEMESIS mod can add android.hardware.wifi; only Hyundai's own flag means a module is fitted.
        TileSupport s = TileSupport.of(features(TileSupport.ANDROID_WIFI, TileSupport.EV), false);
        assertFalse(s.shows(TileSpec.Kind.WIFI));
        assertFalse(s.wifiClient);
        assertTrue(s.shows(TileSpec.Kind.PRECONDITION));
    }

    @Test public void nonHyundaiDeviceUsesAndroidWifiFlag() {
        assertTrue(TileSupport.of(features(TileSupport.ANDROID_WIFI), false).shows(TileSpec.Kind.WIFI));
        assertTrue(TileSupport.of(features(TileSupport.ANDROID_WIFI), false).wifiClient);
        assertFalse(TileSupport.of(features(), false).shows(TileSpec.Kind.WIFI));
    }

    @Test public void simulatedVehicleAlwaysShowsPreconditioning() {
        assertTrue(TileSupport.of(features(), true).shows(TileSpec.Kind.PRECONDITION));
        assertFalse(TileSupport.of(features(), false).shows(TileSpec.Kind.PRECONDITION));
    }

    @Test public void nullFeatureNamesAreIgnored() {
        // PackageManager reports the OpenGL ES version as a FeatureInfo with a null name.
        Set<String> f = features(TileSupport.ANDROID_WIFI);
        f.add(null);
        assertTrue(TileSupport.of(f, false).shows(TileSpec.Kind.WIFI));
    }

    @Test public void ambientTileFollowsFittedEquipmentFlag() {
        assertTrue(TileSupport.of(features(), false, true).shows(TileSpec.Kind.AMBIENT));
        assertFalse(TileSupport.of(features(), false, false).shows(TileSpec.Kind.AMBIENT));
        assertTrue(TileSupport.all().shows(TileSpec.Kind.AMBIENT));
    }
}
