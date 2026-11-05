package com.tilepad;

import android.os.Build;

/** Explicit emulator-only simulation, excluded from the release APK. */
final class VehicleClientFactory {
    static VehiclePort create() {
        if (!simulated()) return new OemVehicleClient();
        return new VehiclePort() {
            boolean requested;
            @Override public String unavailableReason() { return ""; }
            @Override public Reading read() { return new Reading(2, 0, 1, -1); }
            // Debug-only emulator probe for exercising the advisory without vehicle commands.
            @Override public Integer readMinimumBatteryTemperatureC() {
                try {
                    String value = (String) Class.forName("android.os.SystemProperties")
                        .getMethod("get", String.class).invoke(null, "debug.tilepad.battery_min_c");
                    return value.isEmpty() ? null : Integer.valueOf(value);
                } catch (Exception ignored) { return null; }
            }
            @Override public long activationTimeoutMs() { return PreconditioningProfile.activationTimeoutMs(Build.DEVICE); }
            @Override public int warmThresholdC() { return PreconditioningProfile.warmThresholdC(Build.DEVICE); }
            @Override public void beginManual() { requested = true; }
            @Override public void maintainManual() { }
            @Override public void stopAndRelease() { requested = false; }
            @Override public long maintenanceIntervalMs() { return 0; }
        };
    }

    /** True on an emulator, where {@link #create()} returns the simulator. */
    static boolean simulated() { return Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish"); }
}
