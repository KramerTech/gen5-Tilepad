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
        if (c.enabled("sync") || c.enabled("driveronly")) {
            try { Platform.requireDualZone(); }
            catch (Exception e) {
                climate.configure(false, false);
                for (String id : new String[]{"sync", "driveronly"}) if (c.enabled(id)) Platform.status(id, e.getMessage());
                return;
            }
        }
        climate.configure(c.enabled("sync"), c.enabled("driveronly"));
    }
    private void beginHeaters() throws Exception {
        locked(new Action() {
            @Override public void run() throws Exception {
                File bootFile = new File(Platform.STATE, "heaters.boot"); String boot = Platform.boot();
                if (bootFile.isFile() && new String(Files.read(bootFile), "UTF-8").equals(boot)) { resumePassenger(); return; }
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
                            if (c.checked("driver") && fitted(outcome, "Driver seat", "driver"))
                                heat(outcome, "Driver seat", "com.hkmc.micom.usm", "com.hkmc.usm.IUSMService", Platform.vehicle().usm.driverHeat, HeaterPolicy.seatRequest(c.number("driverLevel")));
                            if (c.checked("wheel") && fitted(outcome, "Steering wheel", "wheel"))
                                heat(outcome, "Steering wheel", "com.hkmc.vr", "com.hkmc.vr.IVRService", 16, 1);
                            heaterOutcome = outcome.toString();
                            // The passenger seat heats only for a buckled front passenger: now, or
                            // whenever they buckle up later in this drive.
                            if (c.checked("passenger") && fitted(outcome, "Passenger seat", "passenger")) {
                                Files.write(new File(Platform.STATE, PASSENGER_FILE), (Platform.boot() + " " + heaterOutcome).getBytes("UTF-8"));
                                handler.post(passengerWatch);
                            } else Platform.status("heaters", outcome.length() == 0 ? "Skipped: no heaters selected." : outcome.toString());
                        }
                    });
                } catch (Exception e) { Platform.status("heaters", "Skipped this boot: " + e.getMessage()); }
            }
        }, 3000);
    }
    /** Present while the passenger seat waits for a buckled passenger: "<boot id> <driver/wheel outcome>". */
    private static final String PASSENGER_FILE = "heaters-passenger";
    private String heaterOutcome = "";
    /** The car has been on during the wait, so ignition off now means the drive ended (ACC at boot reads off too). */
    private boolean passengerIgnitionSeen;
    private String passengerStatus;
    private final Runnable passengerWatch = new Runnable() { @Override public void run() { watchPassenger(); } };
    /** A wait left by this boot, after a restart of this service. */
    private void resumePassenger() {
        try {
            String[] saved = new String(Files.read(new File(Platform.STATE, PASSENGER_FILE)), "UTF-8").split(" ", 2);
            if (!saved[0].equals(Platform.boot())) { new File(Platform.STATE, PASSENGER_FILE).delete(); return; }
            heaterOutcome = saved.length > 1 ? saved[1] : "";
            handler.post(passengerWatch);
        } catch (Exception ignored) { }
    }
    private void watchPassenger() {
        try {
            locked(new Action() {
                @Override public void run() throws Exception {
                    File pending = new File(Platform.STATE, PASSENGER_FILE);
                    if (!pending.isFile()) return;
                    Config c = TweaksCommand.load();
                    String stop = null;
                    if (!c.enabled("heaters") || !c.checked("passenger")) stop = "Passenger seat: not heated (turned off in Tweaks).";
                    else if (Platform.ignitionOn()) {
                        passengerIgnitionSeen = true;
                        if (ClimateController.passengerBuckled()) {
                            StringBuilder outcome = new StringBuilder(heaterOutcome);
                            heat(outcome, "Passenger seat (front passenger buckled up)", "com.hkmc.micom.usm", "com.hkmc.usm.IUSMService", Platform.vehicle().usm.passengerHeat, HeaterPolicy.seatRequest(c.number("passengerLevel")));
                            Platform.status("heaters", outcome.toString().trim());
                            pending.delete(); return;
                        }
                    } else if (passengerIgnitionSeen) stop = "Passenger seat: not heated (no one buckled up before the car was turned off).";
                    if (stop != null) { Platform.status("heaters", (heaterOutcome + stop).trim()); pending.delete(); return; }
                    String waiting = (heaterOutcome + "Passenger seat: waiting for the front passenger to buckle up.").trim();
                    if (!waiting.equals(passengerStatus)) { passengerStatus = waiting; Platform.status("heaters", waiting); }
                    handler.postDelayed(passengerWatch, 1000);
                }
            });
        } catch (Exception e) {
            // The belt or ignition couldn't be read yet (services starting): keep watching.
            handler.postDelayed(passengerWatch, 5000);
        }
    }
    /** Whether the car reports this heater at all; trims differ (no heated wheel on some). */
    private static boolean fitted(StringBuilder out, String label, String key) {
        Boolean fitted = fitted(key);
        if (fitted == null) out.append(label).append(": skipped (the car didn't say whether it has one). ");
        else if (!fitted) out.append(label).append(": skipped (this car doesn't have one). ");
        return Boolean.TRUE.equals(fitted);
    }
    /** The car's equipment signal for driver, passenger or wheel: true/false, or null if unreadable. */
    static Boolean fitted(String key) {
        try { return Platform.integer("com.hkmc.micom.usm", "com.hkmc.usm.IUSMService", HeaterPolicy.fittedTransaction(Platform.vehicle().usm, key)) != 0; }
        catch (Exception e) { return null; }
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
