package com.tilepad;

import android.content.Intent;

final class PreviewData {
    static boolean enabled(Intent intent) { return false; }
    static ConnectionSnapshot connection() { return ConnectionSnapshot.unknown(); }
    static AdbSnapshot adb() { return AdbSnapshot.checking(); }
    static TweaksSnapshot tweaks() { return TweaksSnapshot.checking(); }
}
