package com.tilepad;

import java.util.Set;

/**
 * Which core tiles this head unit can back. Hyundai's firmware declares each car's options as
 * system features (/system/etc/permissions/com.hkmc.*.xml), so the same build on a gas car or a
 * unit without the Wi-Fi module simply leaves those tiles out.
 */
final class TileSupport {
    static final String HKMC_PREFIX = "com.hkmc.";
    static final String EV = "com.hkmc.hardware.ev";
    static final String WIFI_MODULE = "com.hkmc.software.wifi.module";
    static final String ANDROID_WIFI = "android.hardware.wifi";

    /** wifiClient: the radio may join networks, not just run the CarPlay/Android Auto hotspot. */
    final boolean wifi, wifiClient, precondition, ambient;

    private TileSupport(boolean wifi, boolean wifiClient, boolean precondition, boolean ambient) {
        this.wifi = wifi; this.wifiClient = wifiClient; this.precondition = precondition; this.ambient = ambient;
    }

    static TileSupport of(Set<String> features, boolean simulatedVehicle) {
        return of(features, simulatedVehicle, true);
    }

    /**
     * On a Hyundai unit the module flag says a radio is fitted, and android.hardware.wifi says
     * client mode is allowed. That is how Hyundai's own Wi-Fi service and settings app read them.
     * Stock US units ship without android.hardware.wifi. Wireless projection mods add it only
     * when asked (HMG Leafia Plus's LEAFIA-WIFI-CLIENT), and so does the Wi-Fi tile.
     * Elsewhere (emulator, test devices) Android's flag decides both.
     * A simulated vehicle (debug build on an emulator) always gets the preconditioning tile.
     */
    static TileSupport of(Set<String> features, boolean simulatedVehicle, boolean ambientFitted) {
        boolean hyundai = false;
        for (String name : features) if (name != null && name.startsWith(HKMC_PREFIX)) { hyundai = true; break; }
        boolean client = features.contains(ANDROID_WIFI);
        boolean wifi = hyundai ? features.contains(WIFI_MODULE) : client;
        return new TileSupport(wifi, wifi && client, simulatedVehicle || features.contains(EV), ambientFitted);
    }

    /** Design previews show every tile. */
    static TileSupport all() { return new TileSupport(true, true, true, true); }

    boolean shows(TileSpec.Kind kind) {
        switch (kind) {
            case WIFI: return wifi;
            case PRECONDITION: return precondition;
            case AMBIENT: return ambient;
            default: return true;
        }
    }
}
