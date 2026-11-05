package com.tilepad.tweakcore;

import android.os.SystemClock;
import java.io.IOException;

final class VehicleLocationSource {
    static VehicleLocation current() throws Exception {
        VehicleLocation fix = VehicleLocation.parse(Platform.exec("dumpsys", "location"));
        if (!fix.usable(SystemClock.elapsedRealtime())) throw new IOException(
            "Waiting for a GPS fix no older than 60 seconds and accurate to 150 metres or better.");
        return fix;
    }
    private VehicleLocationSource() { }
}
