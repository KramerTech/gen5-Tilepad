package com.tilepad;

import android.content.Intent;

/** Emulator-only design fixtures. This implementation is not compiled into release. */
final class PreviewData {
    static boolean enabled(Intent intent) { return intent.getBooleanExtra("design_preview", false); }
    static ConnectionSnapshot connection() {
        return ConnectionSnapshot.read(true, true, "Home Wi-Fi", 0x2a0200c0);
    }
    static AdbSnapshot adb() {
        return AdbSnapshot.of(AdbSnapshot.State.ON, 5555);
    }
    static TweaksSnapshot tweaks() { return TweaksSnapshot.of(4, 8); }
}
