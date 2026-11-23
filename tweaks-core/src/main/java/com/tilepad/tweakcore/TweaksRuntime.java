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
    private final Runnable startup = new Runnable() { @Override public void run() { afterStartup(); } };
    private final Runnable splitOff = new Runnable() { @Override public void run() { closeSplitScreen(); } };
    private boolean bootSettled, consentApplied, handleHidden;
    private int splitTries;
    // Hyundai's split screen (RightScreenHome): the panel the right-hand handle opens. Same interface on every supported image.
    private static final String SPLIT = "com.mobis.splitscreen.right.SplitScreenHome", SPLIT_INTERFACE = "com.hkmc.split.manager.ISplitScreenInterfaces";
    private static final int SPLIT_IS_READY = 0x28, SPLIT_REQUEST_ON = 0xa;
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
        consentApplied = Boolean.parseBoolean(live.getProperty("applied.consent"));
        handleHidden = Boolean.parseBoolean(live.getProperty("applied.nav"));
        if (consentApplied || handleHidden) handler.post(startup);
        if (handleHidden) {
            // Each driver keeps their own split-screen setting, so a driver change can bring the panel back.
            registerRootReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) { splitTries = 0; handler.removeCallbacks(splitOff); handler.postDelayed(splitOff, 3000); }
            }, new IntentFilter("com.hkmc.intent.action.USER_LOGGED_IN"));
        }
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
    /**
     * Skip Startup Safety Brief's patch sends the brief's agreement (AGREE_USER_LICENSE) as SystemUI
     * starts, before the HKMC services that act on it have registered for it: they start later in
     * system_server's boot (HkmcSystemServer.systemReady). Without it the day/night theme schedule
     * stays off (ThemeScheduleService) and the driver's display settings aren't applied, so the
     * screen stays in day mode at night. Once startup has finished, send it again, once per boot,
     * as pressing Confirm would.
     */
    private void afterStartup() {
        try {
            if (!"1".equals(Platform.property("sys.boot_completed"))) { handler.postDelayed(startup, 2000); return; }
        } catch (Exception e) { handler.postDelayed(startup, 2000); return; }
        // A margin past boot_completed for the HKMC services' own startup.
        if (!bootSettled) { bootSettled = true; handler.postDelayed(startup, 10000); return; }
        if (consentApplied) resendAgreement();
        if (handleHidden) closeSplitScreen();
    }
    private void resendAgreement() {
        try {
            // Set by the patched agreement path: the brief really was skipped this boot.
            if (!"true".equals(Platform.property("sys.boot.confirm_screen"))) return;
            File sent = new File(Platform.STATE, "agreement-boot");
            String boot = Platform.boot();
            if (sent.isFile() && boot.equals(new String(Files.read(sent), "UTF-8").trim())) return;
            Platform.exec("am", "broadcast", "-a", "com.hkmc.intent.action.AGREE_USER_LICENSE");
            Files.write(sent, boot.getBytes("UTF-8"));
        } catch (Exception e) { Platform.status("consent", "Could not repeat the startup agreement: " + e.getMessage()); }
    }
    /**
     * Hide Right-Hand Nav Handle hides the handle, which is also the only control that closes
     * Hyundai's split screen: the panel it opens stays on over every app, across restarts (#13).
     * So while the handle is hidden, turn the split screen off the way tapping the handle does
     * (requestSplitScreenOn(isOn false, isUser true, useAni false)), after startup and when a
     * driver logs in. Turning it off when it's already off changes nothing.
     */
    private void closeSplitScreen() {
        try {
            if (Platform.integer(SPLIT, SPLIT_INTERFACE, "isReady", SPLIT_IS_READY) == 0) throw new IOException("the split screen isn't ready");
            Platform.integer(SPLIT, SPLIT_INTERFACE, "requestSplitScreenOn", SPLIT_REQUEST_ON, 0, 1, 0);
        } catch (Exception e) {
            if (++splitTries < 15) { handler.postDelayed(splitOff, 2000); return; }
            Platform.status("split", "Could not turn off the split screen: " + e.getMessage());
        }
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
    /**
     * "<boot id> on|off": the ignition as last handled. Heaters run once per car start (ignition
     * off to on), not per head-unit boot: turning the car off and on often leaves the head unit
     * running. The head unit can also start with the car still off, before the climate system
     * reports the outdoor temperature.
     */
    private static final String DRIVE_FILE = "heaters.drive";
    private static final long IGNITION_MS = 2000;
    private Boolean heaterIgnition;
    /** Bumped when the car turns off, so a wait for the temperature from that drive stops. */
    private int heaterDrive;
    private boolean heaterWaiting;
    private final Runnable ignitionWatch = new Runnable() { @Override public void run() { watchIgnition(); } };
    private void beginHeaters() { handler.post(ignitionWatch); }
    private void watchIgnition() {
        try {
            final boolean on = Platform.ignitionOn();
            if (!Boolean.valueOf(on).equals(heaterIgnition)) {
                locked(new Action() { @Override public void run() throws Exception { onIgnition(on); } });
                heaterIgnition = on;
            }
        } catch (Exception ignored) { } // The body control service is still starting: read it again shortly.
        handler.postDelayed(ignitionWatch, IGNITION_MS);
    }
    private void onIgnition(boolean on) throws Exception {
        File driveFile = new File(Platform.STATE, DRIVE_FILE); String boot = Platform.boot();
        String handled = driveFile.isFile() ? new String(Files.read(driveFile), "UTF-8").trim() : "";
        if (!on) {
            heaterDrive++;
            if (heaterWaiting) { heaterWaiting = false; Platform.status("heaters", "Nothing was heated because the car was turned off before a valid outdoor temperature arrived."); }
            Files.write(driveFile, (boot + " off").getBytes("UTF-8"));
            return;
        }
        // Still the drive this service already handled, after a restart of the service.
        if (handled.equals(boot + " on")) { resumePassenger(); return; }
        // Mark even when off: enabling later applies from the NEXT car start,
        // and a service restart must never reapply heat over manual controls.
        Files.write(driveFile, (boot + " on").getBytes("UTF-8"));
        new File(Platform.STATE, PASSENGER_FILE).delete();
        // The last drive's passenger wait: without this, the same waiting text is never shown again.
        passengerStatus = null; passengerIgnitionSeen = false;
        if (TweaksCommand.load().enabled("heaters")) {
            Platform.status("heaters", "The car was turned on. Waiting up to two minutes for a valid outdoor temperature.");
            heaterWaiting = true;
            heaterAttempt(heaterDrive, SystemClock.elapsedRealtime() + 120000L);
        }
    }
    private void heaterAttempt(final int drive, final long deadline) {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (drive != heaterDrive) return;
                try {
                    locked(new Action() {
                        @Override public void run() throws Exception {
                            if (drive != heaterDrive) return;
                            Config c = TweaksCommand.load();
                            heaterWaiting = false;
                            if (!c.enabled("heaters")) { Platform.status("heaters", "Canceled before applying heat."); return; }
                            Platform.requireVehicle();
                            String dump;
                            try { dump = Platform.exec("dumpsys", "com.hkmc.micom.datc"); }
                            catch (Exception e) {
                                if (SystemClock.elapsedRealtime() < deadline) { heaterWaiting = true; heaterAttempt(drive, deadline); }
                                else Platform.status("heaters", "Skipped this drive: the climate service didn't become available within two minutes of the car being turned on.");
                                return;
                            }
                            Double temperature = HeaterPolicy.outsideC(dump);
                            if (temperature == null) {
                                if (SystemClock.elapsedRealtime() < deadline) { heaterWaiting = true; heaterAttempt(drive, deadline); }
                                else Platform.status("heaters", "Skipped this drive: no valid outdoor temperature arrived within two minutes of the car being turned on. The climate system last reported " + HeaterPolicy.reported(dump) + ".");
                                return;
                            }
                            if (!HeaterPolicy.cold(temperature, c.number("threshold"))) {
                                Platform.status("heaters", "Skipped this drive: the outdoor temperature is at or above your threshold."); return;
                            }
                            StringBuilder outcome = new StringBuilder();
                            if (c.checked("driver") && fitted(outcome, "Driver seat", "driver"))
                                heat(outcome, "Driver seat", "com.hkmc.micom.usm", "com.hkmc.usm.IUSMService", "setFrontSeatHeatDrvStep", Platform.vehicle().usm.driverHeat, HeaterPolicy.seatRequest(c.number("driverLevel")));
                            if (c.checked("wheel") && fitted(outcome, "Steering wheel", "wheel"))
                                heat(outcome, "Steering wheel", "com.hkmc.vr", "com.hkmc.vr.IVRService", "set_StrgWhlHeated_Cmd_AVN", 16, 1);
                            heaterOutcome = outcome.toString();
                            // The passenger seat heats only for a buckled front passenger: now, or
                            // whenever they buckle up later in this drive.
                            if (c.checked("passenger") && fitted(outcome, "Passenger seat", "passenger")) {
                                Files.write(new File(Platform.STATE, PASSENGER_FILE), (Platform.boot() + " " + heaterOutcome).getBytes("UTF-8"));
                                handler.removeCallbacks(passengerWatch); handler.post(passengerWatch);
                            } else Platform.status("heaters", outcome.length() == 0 ? "Skipped: no heaters selected." : outcome.toString());
                        }
                    });
                } catch (Exception e) { Platform.status("heaters", "Skipped this drive: " + e.getMessage()); }
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
            handler.removeCallbacks(passengerWatch); handler.post(passengerWatch);
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
                            heat(outcome, "Passenger seat (front passenger buckled up)", "com.hkmc.micom.usm", "com.hkmc.usm.IUSMService", "setFrontSeatHeatAstStep", Platform.vehicle().usm.passengerHeat, HeaterPolicy.seatRequest(c.number("passengerLevel")));
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
        try { return Platform.integer("com.hkmc.micom.usm", "com.hkmc.usm.IUSMService", HeaterPolicy.fittedMethod(key), HeaterPolicy.fittedTransaction(Platform.vehicle().usm, key)) != 0; }
        catch (Exception e) { return null; }
    }
    private static void heat(StringBuilder out, String label, String service, String descriptor, String method, int code, int value) {
        try { Platform.command(service, descriptor, method, code, value); out.append(label).append(": request accepted. "); }
        catch (Platform.NotSent e) { out.append(label).append(": not sent. ").append(sentence(e.getMessage())); }
        catch (Exception e) { out.append(label).append(": failed (may have partially applied): ").append(sentence(e.getMessage())); }
    }
    /** message as a sentence followed by a space, without doubling its full stop. */
    private static String sentence(String message) {
        String m = message == null ? "" : message.trim();
        return m.isEmpty() ? "" : m + (m.endsWith(".") ? " " : ". ");
    }
    private interface Action { void run() throws Exception; }
    private static void locked(Action action) throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(new File(Platform.STATE, "lock"), "rw"); FileLock ignored = file.getChannel().lock()) { action.run(); }
    }
}
