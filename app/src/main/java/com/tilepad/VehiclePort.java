package com.tilepad;

/** Only the firmware profile may translate these operations into OEM commands. */
interface VehiclePort {
    final class Reading {
        final int enabled, indicator, ready, failureRaw;
        Reading(int enabled, int indicator, int ready, int failureRaw) {
            this.enabled = enabled; this.indicator = indicator;
            this.ready = ready; this.failureRaw = failureRaw;
        }
    }
    String unavailableReason();
    Reading read() throws Exception;
    /** Optional bounded telemetry. Unknown temperature never blocks a manual request. */
    default Integer readMinimumBatteryTemperatureC() { return null; }
    default long activationTimeoutMs() { return PreconditioningController.ACTIVATION_TIMEOUT_MS; }
    default int warmThresholdC() { return BatteryTemperature.EXPECTED_HEATING_THRESHOLD_C; }
    void beginManual() throws Exception;
    void maintainManual() throws Exception;
    void stopAndRelease() throws Exception;
    long maintenanceIntervalMs();
}
