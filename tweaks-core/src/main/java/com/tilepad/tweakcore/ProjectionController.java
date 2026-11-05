package com.tilepad.tweakcore;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import java.io.*;
import java.util.*;

/** Event-driven connection edge detection, independent of the stock OSD renderer. */
final class ProjectionController {
    static final String[] EVENTS = {"com.telechips.android.gpm.set", "com.telechips.android.gpm.finish",
        "com.daudio.app.dipo.start", "com.daudio.app.dipo.close",
        "com.daudio.app.phoneprojection.UPDATE_CARPLAY", "com.daudio.app.phoneprojection.UPDATE_ANDROIDAUTO",
        "android.hardware.usb.action.CARPLAY_DEVICE_ATTACHED", "android.hardware.usb.action.CARPLAY_DEVICE_DETACHED"};
    private static boolean checked;
    private final Context context;
    private final Handler handler;
    private final File state = new File(Platform.STATE, "projection.properties");
    private ProjectionPolicy policy = new ProjectionPolicy(0);
    private boolean enabled;
    private int failures;
    private String lastStatus;
    private final Runnable check = new Runnable() { @Override public void run() { reconcile(); } };
    ProjectionController(Context context, Handler handler) { this.context = context; this.handler = handler; }
    static void requireSupported() throws Exception {
        if (checked) return;
        Platform.requireVehicle();
        if (!"ebff68c02c0d82dbd22fd8ec7cc6f71d96ef653f8d8843c2f1748e80b230d0c8".equals(Files.hash(new File("/system/framework/framework.odex")))
            || !"00d62f627875e9bc2ada061761c70e2dda7ef15a0c1913c085563474d2ffa3e6".equals(Files.hash(new File("/system/framework/framework2.odex"))))
            throw new IOException("Projection interfaces differ from the captured firmware. No screen was opened.");
        checked = true;
    }
    void configure(boolean next) {
        if (next == enabled) return;
        enabled = next; handler.removeCallbacks(check); failures = 0;
        if (!next) {
            policy = new ProjectionPolicy(0);
            try { persist(); } catch (Exception e) { status(e.getMessage()); }
            status("Automatic opening is off. Phone Projection is left as it is."); return;
        }
        try {
            Properties saved = Files.properties(state);
            policy = new ProjectionPolicy(Platform.boot().equals(saved.getProperty("boot")) ? Integer.parseInt(saved.getProperty("seen", "0")) : 0);
        } catch (Exception ignored) { policy = new ProjectionPolicy(0); }
        event();
    }
    void event() { if (enabled) { failures = 0; handler.removeCallbacks(check); handler.postDelayed(check, 250); } }
    private void reconcile() {
        if (!enabled) return;
        try {
            requireSupported();
            // Retain an unknown side instead of inventing a disconnect and opening again later.
            int mask = policy.seen(); boolean known = false;
            try { int status = Platform.integer("gpm", "com.hkmc.gpm.IGpmInterfaces", 1);
                mask = status >= 4 ? mask | 2 : mask & ~2; known = true; } catch (Exception ignored) { }
            try { int status = Platform.integer("dipo", "android.dipo.IDiPOManager", 2);
                mask = status == 4 ? mask | 1 : mask & ~1; known = true; } catch (Exception ignored) { }
            if (!known) throw new IOException("Waiting for the Android Auto or CarPlay service.");
            int before = policy.seen(), target = policy.observe(mask);
            try {
                String foreground = target == 0 ? null : foregroundPackage();
                if (target != 0 && ProjectionPolicy.cameraForeground(foreground)) {
                    policy = new ProjectionPolicy(before);
                    status("Phone Projection is available; waiting for the camera screen to close.");
                    handler.postDelayed(check, 1000); return;
                }
                // Persist before dispatch so restarting the service cannot repeatedly steal focus.
                if (before != mask || !state.isFile()) persist();
                if (target != 0 && TweaksCommand.load().enabled("projection")) {
                    String packageName = target == 1 ? "com.daudio.app.dipo" : "com.google.android.projection.sink";
                    if (!packageName.equals(foreground)) {
                        // The stock pop-over sends these same actions, including its OEM routing.
                        Platform.exec("am", "broadcast", "-a", target == 1
                            ? "com.hkmc.intent.action.DIPO_CUSTOM_SHORTCUT" : "com.hkmc.intent.action.GPM_CUSTOM_SHORTCUT");
                    }
                    status((target == 1 ? "CarPlay" : "Android Auto") + " is available; Phone Projection was opened or was already on screen.");
                } else if (mask == 0) status("Waiting for Android Auto or CarPlay to become available.");
                failures = 0;
            } catch (Exception e) {
                policy = new ProjectionPolicy(before);
                try { persist(); } catch (Exception ignored) { }
                throw e;
            }
        } catch (Exception e) {
            status("Automatic opening paused: " + e.getMessage());
            // Bounded service-startup recovery, never periodic focus stealing.
            if (++failures <= 6) handler.postDelayed(check, 5000);
        }
    }
    private String foregroundPackage() {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningTaskInfo> tasks = manager.getRunningTasks(1);
        return tasks.isEmpty() || tasks.get(0).topActivity == null ? null : tasks.get(0).topActivity.getPackageName();
    }
    private void persist() throws Exception {
        Properties saved = new Properties(); saved.setProperty("boot", Platform.boot()); saved.setProperty("seen", Integer.toString(policy.seen())); Files.store(state, saved);
    }
    private void status(String message) { if (!message.equals(lastStatus)) { lastStatus = message; Platform.status("projection", message); } }
}
