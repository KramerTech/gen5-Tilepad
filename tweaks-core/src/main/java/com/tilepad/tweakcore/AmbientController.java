package com.tilepad.tweakcore;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.util.Calendar;
import java.util.List;
import java.util.Properties;

/**
 * Clean-room controller driving cabin ambient lights through verified OEM Binder calls inside
 * Tilepad's longstar root process. Snapshots and restores owner settings safely, enforces rate
 * limits (<= 10 Hz), yields on external owner edits (3 s window), and avoids GPS or network calls.
 */
final class AmbientController {
    static final String LIVE_FILE = "ambient-live.properties";
    static final String FITTED_FILE = "ambient-fitted";
    private static final String OWNER_FILE = "ambient-owner.properties";
    private static final String SML_SERVICE = "com.hkmc.micom.system";
    private static final String SML_DESCRIPTOR = "com.hkmc.micom.ISystemService";
    static final String ACTION_WEATHER_RESPONSE = "res_weather_info_from_cds_to_tmu";
    static final String EXTRA_WEATHER_PARCEL = "ReadAndSaveWeatherParcelable";
    private static final String ACTION_SML_STATUS_CHANGED = "com.mobis.intent.action.SML_STATUS_CHANGED";
    private static final String EXTRA_SML_STATUS = "com.mobis.intent.extra.SML_STATUS";

    private final Context context;
    private final Handler handler;
    private final AmbientPolicy.WriteTracker writes = new AmbientPolicy.WriteTracker();
    private final AmbientPolicy.MusicTracker music = new AmbientPolicy.MusicTracker();

    private Config config = new Config(new Properties());
    private boolean receiversRegistered, enabled, nightState, ignitionWasOn, ownerYielded;
    private Boolean detectedFitted, tempUnitF;
    private AmbientPolicy.Brand brand = AmbientPolicy.Brand.HYUNDAI;
    private AmbientPolicy.SmlStatus ownerBaseline, latestStatus;
    private AmbientPolicy.ClimateState climateState = AmbientPolicy.ClimateState.OFF;
    private AmbientPolicy.WeatherReport weatherReport;
    private long lastTempCheckMs, lastSmlProbeMs, lastNightSwitchMs, lastWeatherWriteMs;
    private long visualizerRetryAfterMs, lastVisualizerSignalMs;
    private Double outsideTempC;
    private int appliedRgb = -1, appliedLevel = -1;
    private String musicStatus = "Idle";
    private String lastPublishedLive = "";
    private Visualizer visualizer;
    private byte[] visualizerFft, visualizerWave;
    private boolean sawVisualizerSignal;

    private final Runnable tick = new Runnable() { @Override public void run() { step(); } };

    AmbientController(Context context, Handler handler) {
        this.context = context;
        this.handler = handler;
        recoverPendingOwnerState();
        // Register at start: weather broadcast res_weather_info_from_cds_to_tmu is non-sticky.
        ensureReceivers();
    }

    /** Called by TweaksRuntime when the head unit's Day/Night configuration changes. */
    void onConfigurationChanged() {
        if (enabled) schedule(50);
    }

    /** If a previous run died while owning the lamps, restore owner settings cleanly on startup. */
    private void recoverPendingOwnerState() {
        try {
            File ownerFile = new File(Platform.STATE, OWNER_FILE);
            if (!ownerFile.isFile()) return;
            AmbientPolicy.OwnerState saved = AmbientPolicy.decodeOwnerState(Files.properties(ownerFile));
            if (saved != null && saved.dirty) {
                ownerBaseline = saved.status;
                restoreOwnerInternal("Tilepad restored your car's saved lighting after restarting.");
            }
        } catch (Exception e) {
            Platform.status("ambient", "Tilepad couldn't restore the car's saved lighting: " + e.getMessage());
        }
    }

    void configure(Config next) {
        config = next == null ? new Config(new Properties()) : next;
        AmbientPolicy.Mode mode = AmbientPolicy.Mode.of(config.get("ambientMode"));
        boolean shouldRun = config.enabled("ambient") && mode != AmbientPolicy.Mode.OFF;
        if (!shouldRun) {
            boolean wasActive = enabled;
            enabled = false;
            handler.removeCallbacks(tick);
            music.reset();
            closeVisualizer();
            if (wasActive || hasDirtyOwner()) restoreOwnerInternal("Ambient Lighting is off, so your car's saved lighting is back.");
            else publishLive("off", "Off", "Ambient Lighting is off, leaving Vehicle Settings unchanged.", config);
            return;
        }
        if (mode != AmbientPolicy.Mode.MUSIC) closeVisualizer();
        try { brand = AmbientPolicy.brandForProduct(Platform.property("ro.build.product")); }
        catch (Exception ignored) { brand = AmbientPolicy.Brand.HYUNDAI; }
        ownerYielded = false;
        enabled = true;
        ensureReceivers();
        schedule(50);
    }

    /** Restores owner lighting when requested by the user and returns a plain-English result message. */
    static String restoreOwnedLighting() throws Exception {
        File ownerFile = new File(Platform.STATE, OWNER_FILE);
        if (!ownerFile.isFile()) return "Tilepad hadn't changed **Ambient Light** yet, so nothing needed restoring.";
        AmbientPolicy.OwnerState saved = AmbientPolicy.decodeOwnerState(Files.properties(ownerFile));
        if (saved == null || !saved.dirty || saved.status == null) {
            return "Tilepad hadn't changed **Ambient Light** yet, so nothing needed restoring.";
        }
        for (AmbientPolicy.SmlCommand step : AmbientPolicy.buildRestoreSteps(saved.status)) {
            sendCommand(step);
            Thread.sleep(AmbientPolicy.MIN_COMMAND_GAP_MS);
        }
        Files.store(ownerFile, AmbientPolicy.encodeOwnerState(saved.status, false));
        String color = saved.status.driveModeLinked() ? "linked drive-mode color" : AmbientPolicy.colorName(saved.status.rgb());
        return "Restored **Ambient Light** on the car to " + color + " at brightness " + saved.status.level() + ".";
    }

    /** Run from full tweak restore/uninstall paths without failing the rest of restore if the car isn't ready. */
    static void restoreIfDirty() {
        try { restoreOwnedLighting(); }
        catch (Exception e) { Platform.status("ambient", "Could not restore **Ambient Light**: " + e.getMessage()); }
    }

    private void ensureReceivers() {
        if (receiversRegistered) return;
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(ACTION_SML_STATUS_CHANGED);
            f.addAction(ACTION_WEATHER_RESPONSE);
            registerRootReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent intent) {
                    String action = intent == null ? "" : intent.getAction();
                    if (ACTION_SML_STATUS_CHANGED.equals(action)) {
                        byte[] raw = intent.getByteArrayExtra(EXTRA_SML_STATUS);
                        AmbientPolicy.SmlStatus s = AmbientPolicy.parseSmlStatusBytes(raw);
                        if (s != null) onObservedSml(s);
                    } else if (ACTION_WEATHER_RESPONSE.equals(action)) {
                        AmbientPolicy.WeatherReport parsed = parseWeatherIntent(intent, System.currentTimeMillis());
                        if (parsed != null) {
                            weatherReport = parsed;
                            if (enabled) schedule(100);
                        }
                    }
                }
            }, f);
            receiversRegistered = true;
        } catch (Exception e) {
            Platform.status("ambient", "Broadcast listener fallback active: " + e.getMessage());
        }
    }

    static AmbientPolicy.WeatherReport parseWeatherIntent(Intent intent, long nowMs) {
        if (intent == null) return null;
        try {
            Object raw = intent.getParcelableArrayListExtra(EXTRA_WEATHER_PARCEL);
            if (raw == null) raw = intent.getParcelableExtra(EXTRA_WEATHER_PARCEL);
            return AmbientPolicy.parseWeatherContainer(raw, nowMs);
        } catch (Throwable t) {
            return null;
        }
    }

    private void registerRootReceiver(BroadcastReceiver receiver, IntentFilter filter) throws Exception {
        Class<?> dispatcher = Class.forName("android.app.LoadedApk$ReceiverDispatcher");
        java.lang.reflect.Constructor<?> c = dispatcher.getDeclaredConstructor(
            BroadcastReceiver.class, Context.class, Handler.class, android.app.Instrumentation.class, boolean.class);
        c.setAccessible(true);
        Object rd = c.newInstance(receiver, context, handler, null, true);
        Object binderReceiver = dispatcher.getDeclaredMethod("getIIntentReceiver").invoke(rd);
        Object am = Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
        Class.forName("android.app.IActivityManager").getMethod("registerReceiver",
            Class.forName("android.app.IApplicationThread"), String.class,
            Class.forName("android.content.IIntentReceiver"), IntentFilter.class, String.class, int.class)
            .invoke(am, null, null, binderReceiver, filter, null, 0);
    }

    private void schedule(long delayMs) {
        handler.removeCallbacks(tick);
        if (enabled) handler.postDelayed(tick, delayMs);
    }

    private void step() {
        if (!enabled) return;
        long now = SystemClock.elapsedRealtime();
        try {
            Firmware.Release rel = Platform.vehicle();
            if (rel.ambient == null) {
                throw new Platform.NotSent("This firmware's ambient lighting interface isn't supported yet.");
            }
            // Probe equipment and real owner baseline if not yet read this session.
            if (detectedFitted == null || (ownerBaseline == null && now - lastSmlProbeMs >= 5000L)) {
                lastSmlProbeMs = now;
                probeSmlStatus();
            }
            AmbientPolicy.Equipment eq = AmbientPolicy.Equipment.of(config.get("ambientEquipment"));
            if (eq == AmbientPolicy.Equipment.NOT_FITTED || (eq == AmbientPolicy.Equipment.AUTO && Boolean.FALSE.equals(detectedFitted))) {
                closeVisualizer();
                publishLive("not_fitted", "Not fitted", "This car doesn't appear to have built-in **Ambient Light** hardware.", config);
                schedule(10000);
                return;
            }
            boolean ignOn = safeIgnitionOn();
            if (ignOn) ignitionWasOn = true;
            else if (ignitionWasOn) {
                // Drive ended: restore owner lights once when ignition turns off.
                ignitionWasOn = false;
                closeVisualizer();
                if (hasDirtyOwner()) restoreOwnerInternal("The car turned off, so your saved **Ambient Light** is back.");
                schedule(5000);
                return;
            }
            if (ownerYielded) {
                closeVisualizer();
                publishLive("owner_yielded", "Owner control", "Lighting was adjusted on the car in **Ambient Light**, so Tilepad stepped back.", config);
                schedule(5000);
                return;
            }

            AmbientPolicy.Mode mode = AmbientPolicy.Mode.of(config.get("ambientMode"));
            if (mode != AmbientPolicy.Mode.MUSIC) closeVisualizer();
            AmbientPolicy.Target target;
            long nextDelay;

            switch (mode) {
                case MANUAL:
                    target = AmbientPolicy.manualTarget(brand, AmbientPolicy.parseHex(config.get("ambientColor")),
                        config.number("ambientLevel"));
                    nextDelay = 5000;
                    break;
                case DAY_NIGHT:
                    boolean ill = readCarNightState();
                    if (ill != nightState && (lastNightSwitchMs == 0 || now - lastNightSwitchMs >= AmbientPolicy.DAY_NIGHT_DEBOUNCE_MS)) {
                        nightState = ill;
                        lastNightSwitchMs = now;
                    }
                    Double fade = readSunsetFadeFraction();
                    target = AmbientPolicy.dayNightTarget(brand, nightState,
                        config.checked("ambientSunsetFade"), fade,
                        AmbientPolicy.parseHex(config.get("ambientDayColor")), config.number("ambientDayLevel"),
                        AmbientPolicy.parseHex(config.get("ambientNightColor")), config.number("ambientNightLevel"));
                    nextDelay = 2000;
                    break;
                case CLIMATE:
                    refreshDatcInputs(now, true);
                    target = AmbientPolicy.climateTarget(brand, climateState,
                        AmbientPolicy.parseHex(config.get("ambientClimateHeatColor")),
                        AmbientPolicy.parseHex(config.get("ambientClimateCoolColor")),
                        AmbientPolicy.parseHex(config.get("ambientClimateDefrostColor")),
                        AmbientPolicy.parseHex(config.get("ambientClimateOffColor")),
                        config.number("ambientClimateLevel"));
                    nextDelay = 2000;
                    break;
                case WEATHER:
                    refreshDatcInputs(now, false);
                    boolean fresh = AmbientPolicy.isWeatherFresh(weatherReport, System.currentTimeMillis());
                    target = AmbientPolicy.weatherTarget(brand, weatherReport, fresh, outsideTempC,
                        config.number("ambientWeatherColdC"), config.number("ambientWeatherHotC"),
                        isFahrenheit(),
                        AmbientPolicy.parseHex(config.get("ambientWeatherClearColor")),
                        AmbientPolicy.parseHex(config.get("ambientWeatherCloudyColor")),
                        AmbientPolicy.parseHex(config.get("ambientWeatherRainColor")),
                        AmbientPolicy.parseHex(config.get("ambientWeatherSnowColor")),
                        AmbientPolicy.parseHex(config.get("ambientWeatherColdColor")),
                        AmbientPolicy.parseHex(config.get("ambientWeatherMildColor")),
                        AmbientPolicy.parseHex(config.get("ambientWeatherHotColor")),
                        config.number("ambientWeatherLevel"));
                    nextDelay = 5000;
                    break;
                case MUSIC:
                    boolean parked = isParked(rel);
                    AmbientPolicy.MusicDriveBehavior driveBehavior = AmbientPolicy.MusicDriveBehavior.of(config.get("ambientMusicDrive"));
                    double[] sampled = sampleCarAudioEnergy(parked, now);
                    double[] bands = new double[]{sampled[1], sampled[2], sampled[3], sampled[4]};
                    target = music.update(brand, AmbientPolicy.MusicStyle.of(config.get("ambientMusicStyle")),
                        driveBehavior,
                        AmbientPolicy.parseHex(config.get("ambientColor")), config.number("ambientMusicLevel"),
                        parked, sampled[0], bands, now);
                    if (parked && target.active) {
                        target = new AmbientPolicy.Target(true, target.rgb, target.level, target.summary,
                            target.detail + " Source: " + musicStatus + ".");
                    }
                    nextDelay = parked ? 250 : (driveBehavior == AmbientPolicy.MusicDriveBehavior.SLOW_CYCLE ? AmbientPolicy.MUSIC_CYCLE_STEP_MS : 1500);
                    break;
                default:
                    target = new AmbientPolicy.Target(false, 0, 0, "Off", "Ambient Lighting is off, leaving Vehicle Settings unchanged.");
                    nextDelay = 5000;
                    break;
            }

            if (!target.active) {
                if (hasDirtyOwner()) restoreOwnerInternal(target.detail);
                publishLive("paused", target.summary, target.detail, config);
                schedule(nextDelay);
                return;
            }
            if (mode == AmbientPolicy.Mode.WEATHER && now - lastWeatherWriteMs < AmbientPolicy.WEATHER_COOLDOWN_MS
                && appliedRgb >= 0 && Math.abs(target.level - appliedLevel) == 0) {
                publishLive("active", target.summary, target.detail, config);
                schedule(nextDelay);
                return;
            }

            if (!ensureOwnerSnapshot()) {
                publishLive("waiting", "Waiting", "Waiting to read the car's current **Ambient Light** settings before making changes.", config);
                schedule(5000);
                return;
            }
            if (writes.canSend(now)) {
                applyTarget(target, now);
                if (mode == AmbientPolicy.Mode.WEATHER) lastWeatherWriteMs = now;
            }
            publishLive("active", target.summary, target.detail, config);
            schedule(nextDelay);
        } catch (Exception e) {
            publishLive("unavailable", "Unavailable", "Ambient Lighting couldn't talk to the car: " + e.getMessage(), config);
            schedule(10000);
        }
    }

    private void applyTarget(AmbientPolicy.Target target, long now) throws Exception {
        int brightRaw = AmbientPolicy.levelToRaw(target.level);
        if (appliedRgb != target.rgb) {
            sendCommand(AmbientPolicy.SmlCommand.config(AmbientPolicy.SML_MODE_DRIVE_LINK, AmbientPolicy.SML_OFF));
            writes.recordConfig(AmbientPolicy.SML_MODE_DRIVE_LINK, AmbientPolicy.SML_OFF, now);
            sendCommand(AmbientPolicy.SmlCommand.rgb(target.rgb));
            writes.recordRgb(target.rgb, now);
            appliedRgb = target.rgb;
        }
        if (appliedLevel != target.level) {
            sendCommand(AmbientPolicy.SmlCommand.config(AmbientPolicy.SML_MODE_BRIGHTNESS, brightRaw));
            writes.recordConfig(AmbientPolicy.SML_MODE_BRIGHTNESS, brightRaw, now);
            appliedLevel = target.level;
        }
    }

    private static void sendCommand(AmbientPolicy.SmlCommand step) throws Exception {
        Firmware.Release rel = Platform.vehicle();
        if (rel.ambient == null) throw new Platform.NotSent("Verified ambient transactions required.");
        if (step.kind == AmbientPolicy.SmlCommand.Kind.CONFIG) {
            int code = Platform.transaction(SML_DESCRIPTOR, "setSMLConfiguration", rel.ambient.setSmlConfig, false);
            Platform.command(SML_SERVICE, SML_DESCRIPTOR, code, step.mode, step.data);
            return;
        }
        int code = Platform.transaction(SML_DESCRIPTOR, "setSMLRGB", rel.ambient.setSmlRgb, false);
        IBinder binder = Platform.service(SML_SERVICE, SML_DESCRIPTOR);
        byte[][] channels = AmbientPolicy.encodeRgb(step.rgb);
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SML_DESCRIPTOR);
            data.writeByteArray(channels[0]);
            data.writeByteArray(channels[1]);
            data.writeByteArray(channels[2]);
            if (!binder.transact(code, data, reply, 0)) throw new IOException("OEM ambient RGB request refused");
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Captures and persists the car's genuine SML lighting state; never invents a fake default baseline. */
    private boolean ensureOwnerSnapshot() throws Exception {
        File ownerFile = new File(Platform.STATE, OWNER_FILE);
        if (ownerFile.isFile()) {
            AmbientPolicy.OwnerState st = AmbientPolicy.decodeOwnerState(Files.properties(ownerFile));
            if (st != null && st.status != null) {
                ownerBaseline = st.status;
                if (!st.dirty) Files.store(ownerFile, AmbientPolicy.encodeOwnerState(ownerBaseline, true));
                return true;
            }
        }
        if (ownerBaseline == null) probeSmlStatus();
        if (ownerBaseline == null) return false;
        Files.store(ownerFile, AmbientPolicy.encodeOwnerState(ownerBaseline, true));
        return true;
    }

    private boolean hasDirtyOwner() {
        try {
            AmbientPolicy.OwnerState st = AmbientPolicy.decodeOwnerState(Files.properties(new File(Platform.STATE, OWNER_FILE)));
            return st != null && st.dirty;
        } catch (Exception e) { return false; }
    }

    private void restoreOwnerInternal(String message) {
        appliedRgb = appliedLevel = -1;
        writes.reset();
        try {
            File ownerFile = new File(Platform.STATE, OWNER_FILE);
            AmbientPolicy.OwnerState st = AmbientPolicy.decodeOwnerState(Files.properties(ownerFile));
            if (st != null && st.dirty && st.status != null) {
                for (AmbientPolicy.SmlCommand step : AmbientPolicy.buildRestoreSteps(st.status)) {
                    sendCommand(step);
                }
                Files.store(ownerFile, AmbientPolicy.encodeOwnerState(st.status, false));
            }
        } catch (Exception e) {
            Platform.status("ambient", message + " (Could not finish restore: " + e.getMessage() + ")");
            return;
        }
        publishLive("off", "Owner", message, config);
    }

    private void probeSmlStatus() {
        try {
            Firmware.Release rel = Platform.vehicle();
            if (rel.ambient == null) return;
            int code = Platform.transaction(SML_DESCRIPTOR, "requestSMLStatus", rel.ambient.requestSml, false);
            IBinder binder = Platform.service(SML_SERVICE, SML_DESCRIPTOR);
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(SML_DESCRIPTOR);
                binder.transact(code, data, reply, 0);
                reply.readException();
            } finally {
                data.recycle();
                reply.recycle();
            }
            // If broadcast didn't populate latestStatus yet, parse recent SystemService:D SML reply log.
            if (latestStatus == null) {
                String out = Platform.exec("logcat", "-b", "system", "-d", "-v", "brief", "-s", "SystemService:D");
                String[] lines = out.split("\n");
                for (int i = lines.length - 1; i >= 0; i--) {
                    if (lines[i].contains("SYS_SML_STATUS_M_RESP")) {
                        AmbientPolicy.SmlStatus parsed = AmbientPolicy.parseSmlStatusLine(lines[i]);
                        if (parsed != null) { onObservedSml(parsed); break; }
                    }
                }
            }
        } catch (Exception ignored) { }
    }

    private void onObservedSml(AmbientPolicy.SmlStatus s) {
        latestStatus = s;
        boolean valid = AmbientPolicy.validEquipment(s);
        detectedFitted = valid;
        writeFittedMarker(valid);
        long now = SystemClock.elapsedRealtime();
        if (enabled && !writes.isSelfEcho(s, appliedRgb, appliedLevel, now)) {
            if (hasDirtyOwner()) {
                ownerBaseline = s;
                try { Files.store(new File(Platform.STATE, OWNER_FILE), AmbientPolicy.encodeOwnerState(s, false)); }
                catch (Exception ignored) { }
                ownerYielded = true;
                publishLive("owner_yielded", "Owner control", "Lighting was adjusted on the car in **Ambient Light**, so Tilepad stepped back.", config);
            } else {
                ownerBaseline = s;
            }
        }
    }

    /** Fallback Day/Night source when sunrise/sunset times are absent: Android Configuration.uiMode night state. */
    private boolean readCarNightState() {
        try {
            int mask = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            if (mask == Configuration.UI_MODE_NIGHT_YES) return true;
            if (mask == Configuration.UI_MODE_NIGHT_NO) return false;
        } catch (Exception ignored) { }
        return nightState;
    }

    private Double readSunsetFadeFraction() {
        if (!config.checked("ambientSunsetFade")) return null;
        if (!AmbientPolicy.isWeatherFresh(weatherReport, System.currentTimeMillis())) return null;
        int sunrise = AmbientPolicy.parseClockMinutes(weatherReport.sunriseHHMM);
        int sunset = AmbientPolicy.parseClockMinutes(weatherReport.sunsetHHMM);
        if (sunrise < 0 || sunset <= sunrise) return null;
        Calendar cal = Calendar.getInstance();
        int nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        return AmbientPolicy.sunsetFadeFraction(nowMinutes, sunrise, sunset);
    }

    private void refreshDatcInputs(long now, boolean fast) {
        long minInterval = fast ? 1500L : 10000L;
        if (now - lastTempCheckMs < minInterval) return;
        lastTempCheckMs = now;
        try {
            String dump = Platform.exec("dumpsys", "com.hkmc.micom.datc");
            outsideTempC = HeaterPolicy.outsideC(dump);
            Boolean f = AmbientPolicy.parseDatcFahrenheit(dump);
            if (f != null) tempUnitF = f;
            int op = AmbientPolicy.parseDumpInt(dump, "mOpStatus", 0);
            int drMode = AmbientPolicy.parseDumpInt(dump, "mDrMode", 0);
            int psMode = AmbientPolicy.parseDumpInt(dump, "mPsMode", 0);
            int autoMode = AmbientPolicy.parseDumpInt(dump, "mAuto", 0);
            int autoDefog = AmbientPolicy.parseDumpInt(dump, "mAutoDefogBlink", 0);
            int heat = AmbientPolicy.parseDumpInt(dump, "mHeatOnOff", 0);
            int ac = AmbientPolicy.parseDumpInt(dump, "mAcOnOff", 0);
            climateState = AmbientPolicy.classifyClimate(op, drMode, psMode, autoMode, autoDefog, heat, ac);
        } catch (Exception ignored) { }
    }

    private boolean isFahrenheit() {
        return tempUnitF != null ? tempUnitF : "F".equals(config.get("units"));
    }

    private boolean safeIgnitionOn() {
        try { return Platform.ignitionOn(); } catch (Exception e) { return true; }
    }

    private boolean isParked(Firmware.Release rel) {
        try {
            int confirmed = rel.ambient != null ? rel.ambient.gearP : 0;
            int code = Platform.transaction(SML_DESCRIPTOR, "getGearPStatus", confirmed, true);
            return Platform.integer(SML_SERVICE, SML_DESCRIPTOR, code) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void closeVisualizer() {
        sawVisualizerSignal = false;
        if (visualizer != null) {
            try { visualizer.setEnabled(false); } catch (Throwable ignored) { }
            try { visualizer.release(); } catch (Throwable ignored) { }
            visualizer = null;
        }
    }

    /** Reads Android output-mix session 0 audio frames (AA, BT, USB, Sounds of Nature); returns null if unavailable or silent. */
    private double[] readVisualizerBands(long nowMs) {
        try {
            if (visualizer == null) {
                if (nowMs < visualizerRetryAfterMs) return null;
                Visualizer v = null;
                try {
                    v = new Visualizer(0);
                    int[] range = Visualizer.getCaptureSizeRange();
                    int minSize = (range != null && range.length >= 1) ? range[0] : 128;
                    int maxSize = (range != null && range.length >= 2) ? range[1] : 1024;
                    int size = Math.max(minSize, Math.min(maxSize, 256));
                    v.setCaptureSize(size);
                    try { v.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED); } catch (Throwable ignored) { }
                    v.setEnabled(true);
                    visualizerFft = new byte[size];
                    visualizerWave = new byte[size];
                    visualizer = v;
                    v = null;
                } finally {
                    if (v != null) {
                        try { v.setEnabled(false); } catch (Throwable ignored) { }
                        try { v.release(); } catch (Throwable ignored) { }
                    }
                }
            }
            int s1 = visualizer.getFft(visualizerFft);
            if (s1 != Visualizer.SUCCESS) {
                if (s1 == Visualizer.ERROR_DEAD_OBJECT) {
                    closeVisualizer();
                    visualizerRetryAfterMs = nowMs + 5000L;
                }
                return null;
            }
            int s2 = visualizer.getWaveForm(visualizerWave);
            return AmbientPolicy.analyzeVisualizerFft(visualizerFft, s2 == Visualizer.SUCCESS ? visualizerWave : null);
        } catch (Throwable t) {
            closeVisualizer();
            visualizerRetryAfterMs = nowMs + 10000L;
            return null;
        }
    }

    /**
     * Reads Android audio output-mix bands via Visualizer(0) while parked (AA, Bluetooth, USB, Sounds
     * of Nature), and falls back cleanly to a gentle timed wave for sources bypassing AudioFlinger
     * (e.g. CarPlay on pcmC0D2p or tuner radio). Never opens the cabin microphone.
     */
    private double[] sampleCarAudioEnergy(boolean parked, long now) {
        if (!parked) {
            closeVisualizer();
            musicStatus = AmbientPolicy.MusicDriveBehavior.of(config.get("ambientMusicDrive")) == AmbientPolicy.MusicDriveBehavior.SLOW_CYCLE
                ? "Slow Color Cycle in Drive"
                : "Paused in Drive";
            return new double[]{0, 0, 0, 0, 0};
        }
        int audioMode = AudioManager.MODE_NORMAL;
        boolean musicActive = false;
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                audioMode = am.getMode();
                musicActive = am.isMusicActive();
            }
        } catch (Exception ignored) { }
        boolean voicePcmRunning = false;
        try {
            String voicePcm = new String(Files.read(new File("/proc/asound/card0/pcm0p/sub0/status")), "UTF-8");
            voicePcmRunning = voicePcm.contains("RUNNING");
        } catch (Exception ignored) { }
        if (AmbientPolicy.isPhoneCallAudioMode(audioMode, voicePcmRunning)) {
            closeVisualizer();
            musicStatus = "Phone call";
            return new double[]{0.15, 0.2, 0.1, 0.1, 0.1};
        }
        boolean pcmRunning = false;
        try {
            String pcm = new String(Files.read(new File("/proc/asound/card0/pcm2p/sub0/status")), "UTF-8");
            pcmRunning = pcm.contains("RUNNING");
        } catch (Exception ignored) { }
        boolean active = pcmRunning || musicActive;
        if (!active) {
            closeVisualizer();
            musicStatus = "Audio idle";
            return new double[]{0.15, 0.2, 0.1, 0.1, 0.1};
        }
        double[] liveBands = readVisualizerBands(now);
        if (liveBands != null) {
            sawVisualizerSignal = true;
            lastVisualizerSignalMs = now;
            musicStatus = "Android audio";
            return liveBands;
        }
        if (sawVisualizerSignal && (now - lastVisualizerSignalMs) < 1500L) {
            musicStatus = "Android audio";
            return new double[]{0.05, 0.0, 0.0, 0.0, 0.0};
        }
        sawVisualizerSignal = false;
        musicStatus = pcmRunning ? "Car audio (timed pulse)" : "Media active (timed pulse)";
        double wave = 0.5 + 0.35 * Math.sin(now / 450.0);
        double phase2 = 0.5 + 0.35 * Math.sin(now / 700.0 + 1.5);
        double phase3 = 0.5 + 0.35 * Math.cos(now / 950.0);
        return new double[]{wave, wave * 0.9, phase2, phase3, wave * 0.6};
    }

    private void writeFittedMarker(boolean valid) {
        try {
            File f = new File(Platform.LIVE, FITTED_FILE);
            Files.write(f, Boolean.toString(valid).getBytes("UTF-8"));
            Platform.LIVE.setReadable(true, false); Platform.LIVE.setExecutable(true, false); f.setReadable(true, false);
        } catch (Exception ignored) { }
    }

    private void publishLive(String state, String headline, String detail, Config c) {
        String statusText = detail != null && !detail.isEmpty() ? detail : headline;
        int rgb = appliedRgb >= 0 ? appliedRgb : AmbientPolicy.parseHex(c.get("ambientColor"));
        int lv = appliedLevel >= 0 ? appliedLevel : c.number("ambientLevel");
        String modeId = AmbientPolicy.Mode.of(c.get("ambientMode")).id;
        String eqId = AmbientPolicy.Equipment.of(c.get("ambientEquipment")).id;
        String liveKey = state + "|" + modeId + "|" + eqId + "|" + detectedFitted + "|" + rgb + "|" + lv
            + "|" + headline + "|" + statusText + "|" + nightState + "|" + outsideTempC + "|" + musicStatus;
        if (liveKey.equals(lastPublishedLive)) return;
        lastPublishedLive = liveKey;
        Platform.status("ambient", statusText);
        try {
            Properties p = new Properties();
            p.setProperty("state", state);
            p.setProperty("mode", modeId);
            p.setProperty("equipment", eqId);
            if (detectedFitted != null) p.setProperty("detectedFitted", Boolean.toString(detectedFitted));
            p.setProperty("colorHex", AmbientPolicy.formatHex(rgb));
            p.setProperty("colorName", AmbientPolicy.colorName(rgb));
            p.setProperty("level", Integer.toString(lv));
            p.setProperty("headline", headline);
            p.setProperty("detail", detail == null ? "" : detail);
            p.setProperty("dayNight", nightState ? "Night" : "Day");
            p.setProperty("unit", isFahrenheit() ? "F" : "C");
            if (outsideTempC != null) p.setProperty("outsideC", String.format(java.util.Locale.US, "%.1f", outsideTempC));
            p.setProperty("musicSource", musicStatus);
            File live = new File(Platform.LIVE, LIVE_FILE);
            Files.store(live, p);
            Platform.LIVE.setReadable(true, false); Platform.LIVE.setExecutable(true, false); live.setReadable(true, false);
        } catch (Exception ignored) { }
    }
}
