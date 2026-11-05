package com.tilepad.tweakcore;

import android.content.*;
import android.database.ContentObserver;
import android.os.*;
import android.provider.Settings;
import java.io.*;
import java.nio.channels.FileLock;
import java.util.Properties;

/** Runs inside the longstar service. One sleeping looper; no polling for DST or static tweaks. */
public final class TweaksRuntime {
    private Handler handler;
    private FileObserver observer;
    private Context context;
    // Retain the framework dispatchers for this process's registered receivers.
    private final java.util.List<Object> receiverDispatchers = new java.util.ArrayList<>();
    private int dstFailures;
    private Config lastDstConfig;
    private ClimateController climate;
    private ProjectionController projection;
    private ChargeAwayController chargeAway;
    private final Runnable configReload = new Runnable() { @Override public void run() {
        try {
            Config c = TweaksCommand.load();
            applyClimate(c);
            if (projection != null) projection.configure(c.enabled("projection"));
            if (chargeAway != null) chargeAway.configure(c);
            if (!c.dstChanged(lastDstConfig)) return;
        }
        catch (Exception e) { Platform.status("runtime", "Could not reload tweaks: " + e.getMessage()); return; }
        reload.run();
    } };
    private final Runnable dst = new Runnable() { @Override public void run() { syncDst(); } };
    private final Runnable reload = new Runnable() { @Override public void run() { handler.removeCallbacks(dst); dstFailures = 0; syncDst(); } };
    private TweaksRuntime() { }
    public static void start() {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try { new TweaksRuntime().run(); }
                catch (Throwable e) { Platform.status("runtime", "Tweak listeners stopped: " + e); }
            }
        }, "tilepad-tweaks");
        t.setDaemon(true); t.start();
    }
    private void run() throws Exception {
        Looper.prepareMainLooper();
        handler = new Handler(Looper.myLooper());
        Object activityThread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        context = (Context) activityThread.getClass().getMethod("getSystemContext").invoke(activityThread);
        Platform.initialize();
        try { locked(new Action() { @Override public void run() throws Exception { TweaksCommand.recover(); } }); }
        catch (Exception e) { Platform.status("recovery", e.getMessage()); }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIME_CHANGED); filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_DATE_CHANGED); filter.addAction(Intent.ACTION_SCREEN_ON);
        registerRootReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { handler.removeCallbacks(reload); handler.post(reload); }
        }, filter);
        // Also keep the stock settings checkbox in step after a manual edit.
        context.getContentResolver().registerContentObserver(Settings.System.getUriFor("summer_time"), false, new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange) { handler.removeCallbacks(reload); handler.post(reload); }
        });
        observer = new FileObserver(Platform.STATE.getPath(), FileObserver.MOVED_TO | FileObserver.CLOSE_WRITE) {
            @Override public void onEvent(int event, String path) {
                if ("config.properties".equals(path)) { handler.removeCallbacks(configReload); handler.post(configReload); }
            }
        };
        observer.startWatching();
        Properties live = new Properties(); live.setProperty("boot", Platform.boot());
        live.setProperty("pid", Integer.toString(android.os.Process.myPid()));
        // File tweaks in effect are the ones configured as this boot began. A later restart of
        // this service in the same boot keeps that first record, so the app can say what still
        // waits for a head-unit restart.
        Properties earlier = new Properties();
        try { earlier = Files.properties(new File(Platform.STATE, "runtime.properties")); } catch (Exception ignored) { }
        boolean sameBoot = Platform.boot().equals(earlier.getProperty("boot"));
        Config atStart = TweaksCommand.load();
        for (String id : Config.FILE_IDS) {
            String key = "applied." + id;
            live.setProperty(key, sameBoot && earlier.containsKey(key) ? earlier.getProperty(key) : Boolean.toString(atStart.enabled(id)));
        }
        Files.store(new File(Platform.STATE, "runtime.properties"), live);
        Platform.status("runtime", "Listening in Tilepad's key-listener process; no separate tweaks service.");
        syncDst();
        beginHeaters();
        climate = new ClimateController(handler);
        Config config = TweaksCommand.load();
        applyClimate(config);
        projection = new ProjectionController(context, handler);
        chargeAway = new ChargeAwayController(context);
        projection.configure(config.enabled("projection")); chargeAway.configure(config);
        Looper.loop();
    }
    private void registerRootReceiver(BroadcastReceiver receiver, IntentFilter filter) throws Exception {
        // app_process is not attached to ActivityManager as an ordinary app.
        // Context.registerReceiver supplies its unattached ApplicationThread and
        // AMS rejects it. A root/system caller registers with a null caller thread.
        // Keep Android's own dispatcher so ordered broadcasts are finished normally.
        Class<?> dispatcher = Class.forName("android.app.LoadedApk$ReceiverDispatcher");
        java.lang.reflect.Constructor<?> constructor = dispatcher.getDeclaredConstructor(
            BroadcastReceiver.class, Context.class, Handler.class, android.app.Instrumentation.class, boolean.class);
        constructor.setAccessible(true);
        Object receiverDispatcher = constructor.newInstance(receiver, context, handler, null, true);
        receiverDispatchers.add(receiverDispatcher);
        java.lang.reflect.Method getReceiver = dispatcher.getDeclaredMethod("getIIntentReceiver");
        getReceiver.setAccessible(true);
        Object binderReceiver = getReceiver.invoke(receiverDispatcher);
        Object manager = Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
        Class.forName("android.app.IActivityManager").getMethod("registerReceiver",
            Class.forName("android.app.IApplicationThread"), String.class,
            Class.forName("android.content.IIntentReceiver"), IntentFilter.class, String.class, int.class)
            .invoke(manager, null, null, binderReceiver, filter, null, 0);
    }
    private void syncDst() {
        try {
            locked(new Action() {
                @Override public void run() throws Exception {
                    Config c = TweaksCommand.load();
                    lastDstConfig = c;
                    if (!c.enabled("dst")) return;
                    long now = System.currentTimeMillis();
                    long next = DstController.update(c, now);
                    dstFailures = 0;
                    if (next != Long.MAX_VALUE) handler.postDelayed(dst, Math.max(1000, next - now));
                }
            });
        } catch (Exception e) {
            Platform.status("dst", "Automatic DST could not run: " + e.getMessage());
            // Bounded startup recovery, then wait for a real clock/config event.
            if (++dstFailures <= 6) handler.postDelayed(dst, 10000);
        }
    }
    private void applyClimate(Config c) {
        climate.configure(c.enabled("sync"), c.enabled("driveronly"));
    }
    private void beginHeaters() throws Exception {
        locked(new Action() {
            @Override public void run() throws Exception {
                File bootFile = new File(Platform.STATE, "heaters.boot"); String boot = Platform.boot();
                if (bootFile.isFile() && new String(Files.read(bootFile), "UTF-8").equals(boot)) return;
                // Mark even when off: enabling later schedules the NEXT boot,
                // and a service restart must never reapply heat over manual controls.
                Files.write(bootFile, boot.getBytes("UTF-8"));
                if (TweaksCommand.load().enabled("heaters")) {
                    Platform.status("heaters", "Waiting up to two minutes for a valid outdoor temperature.");
                    heaterAttempt(SystemClock.elapsedRealtime() + 120000L);
                }
            }
        });
    }
    private void heaterAttempt(final long deadline) {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    locked(new Action() {
                        @Override public void run() throws Exception {
                            Config c = TweaksCommand.load();
                            if (!c.enabled("heaters")) { Platform.status("heaters", "Canceled before applying heat."); return; }
                            Platform.requireVehicle();
                            Double temperature;
                            try { temperature = HeaterPolicy.outsideC(Platform.exec("dumpsys", "com.hkmc.micom.datc")); }
                            catch (Exception e) {
                                if (SystemClock.elapsedRealtime() < deadline) heaterAttempt(deadline);
                                else Platform.status("heaters", "Skipped this boot: climate service did not become available within two minutes.");
                                return;
                            }
                            if (temperature == null) {
                                if (SystemClock.elapsedRealtime() < deadline) heaterAttempt(deadline);
                                else Platform.status("heaters", "Skipped this boot: no valid outdoor temperature arrived within two minutes.");
                                return;
                            }
                            if (!HeaterPolicy.cold(temperature, c.number("threshold"))) {
                                Platform.status("heaters", "Skipped this boot: outdoor temperature is at or above your threshold."); return;
                            }
                            StringBuilder outcome = new StringBuilder();
                            if (c.checked("driver")) heat(outcome, "Driver seat", "com.hkmc.micom.usm", "com.hkmc.usm.IUSMService", 213, HeaterPolicy.seatRequest(c.number("driverLevel")));
                            if (c.checked("wheel")) heat(outcome, "Steering wheel", "com.hkmc.vr", "com.hkmc.vr.IVRService", 16, 1);
                            if (c.checked("passenger")) heat(outcome, "Passenger seat", "com.hkmc.micom.usm", "com.hkmc.usm.IUSMService", 214, HeaterPolicy.seatRequest(c.number("passengerLevel")));
                            Platform.status("heaters", outcome.length() == 0 ? "Skipped: no heaters selected." : outcome.toString());
                        }
                    });
                } catch (Exception e) { Platform.status("heaters", "Skipped this boot: " + e.getMessage()); }
            }
        }, 3000);
    }
    private static void heat(StringBuilder out, String label, String service, String descriptor, int code, int value) {
        try { Platform.command(service, descriptor, code, value); out.append(label).append(": request accepted. "); }
        catch (Exception e) { out.append(label).append(": failed (may have partially applied): ").append(e.getMessage()).append(". "); }
    }
    private interface Action { void run() throws Exception; }
    private static void locked(Action action) throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(new File(Platform.STATE, "lock"), "rw"); FileLock ignored = file.getChannel().lock()) { action.run(); }
    }
}
