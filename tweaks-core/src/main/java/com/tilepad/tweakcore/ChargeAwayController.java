package com.tilepad.tweakcore;

import android.content.Context;
import android.os.*;
import java.io.*;
import java.util.*;
import java.nio.channels.FileLock;

/** Polls only while enabled and Home is set, inside Tilepad's existing runtime. */
final class ChargeAwayController {
    private final Context context;
    private Handler handler;
    private HandlerThread worker;
    private final File state = new File(Platform.STATE, "charge-away.properties");
    private boolean enabled;
    private String homeKey = "", lastStatus;
    private double latitude, longitude;
    private ChargeAwayPolicy.Zone zone = ChargeAwayPolicy.Zone.UNKNOWN;
    private int pendingMode = -1, attempts;
    private final Runnable poll = new Runnable() { @Override public void run() {
        if (!enabled || homeKey.isEmpty()) return;
        try {
            Config live = TweaksCommand.load();
            if (live.enabled("chargeaway") && homeKey.equals(live.get("homeLat") + "," + live.get("homeLon"))) step();
        }
        catch (Exception e) { status("Charge-Away paused: " + e.getMessage()); }
        finally { if (enabled && !homeKey.isEmpty()) handler.postDelayed(this, 10000); }
    } };
    ChargeAwayController(Context context) { this.context = context; }
    synchronized void configure(Config source) {
        if (handler == null) {
            if (!source.enabled("chargeaway") || !source.hasHome()) {
                // The Tweaks app says to set Home; there's nothing running to report.
                if (source.enabled("chargeaway")) status("");
                return;
            }
            worker = new HandlerThread("tilepad-charge-away"); worker.setDaemon(true); worker.start();
            handler = new Handler(worker.getLooper());
        }
        final Config config = new Config((Properties) source.values.clone());
        handler.post(new Runnable() { @Override public void run() { configureWorker(config); } });
    }
    private void configureWorker(Config config) {
        String key = config.hasHome() ? config.get("homeLat") + "," + config.get("homeLon") : "";
        boolean next = config.enabled("chargeaway");
        if (next == enabled && key.equals(homeKey)) return;
        handler.removeCallbacks(poll); enabled = next; homeKey = key; pendingMode = -1; attempts = 0;
        zone = ChargeAwayPolicy.Zone.UNKNOWN;
        if (!next || key.isEmpty()) { status(""); return; }
        latitude = Double.parseDouble(config.get("homeLat")); longitude = Double.parseDouble(config.get("homeLon"));
        try {
            Properties saved = Files.properties(state);
            if (key.equals(saved.getProperty("home"))) zone = ChargeAwayPolicy.Zone.valueOf(saved.getProperty("zone", "UNKNOWN"));
        } catch (Exception ignored) { }
        handler.post(poll);
    }
    private void step() throws Exception {
        VehicleLocation fix = VehicleLocationSource.current();
        double metres = ChargeAwayPolicy.distance(latitude, longitude, fix.latitude, fix.longitude);
        ChargeAwayPolicy.Zone next = ChargeAwayPolicy.zone(zone, metres, fix.accuracy);
        if (next != zone) {
            Properties saved = new Properties(); saved.setProperty("home", homeKey); saved.setProperty("zone", next.name());
            Files.store(state, saved); zone = next; pendingMode = -1; attempts = 0;
        }
        if (zone == ChargeAwayPolicy.Zone.UNKNOWN) { status("Between 1 and 1.5 miles of Home; keeping Scheduled Charging unchanged until a boundary is crossed."); return; }
        int[] current = ScheduledCharging.read();
        boolean wanted = zone == ChargeAwayPolicy.Zone.HOME;
        String distance = String.format(Locale.US, "%.2f miles from Home. ", metres / ChargeAwayPolicy.MILE_M);
        if (ChargeAwayPolicy.enabled(current) == wanted) {
            pendingMode = -1; attempts = 0;
            status(distance + "Scheduled Charging is " + (wanted ? "on." : "off.")); return;
        }
        if (wanted && !ChargeAwayPolicy.configured(current)) throw new IOException("Set your off-peak charging times in EV Settings first.");
        int desired = ChargeAwayPolicy.mode(zone, current, wanted ? ScheduledCharging.preference(context) : -1);
        if (pendingMode != desired) { pendingMode = desired; attempts = 0; }
        if (attempts >= 3) { status(distance + "The car has not confirmed Scheduled Charging " + (wanted ? "on" : "off") + ". No more requests until the state, Home, or tweak selection changes, or Tilepad's service restarts."); return; }
        ScheduledCharging.Prepared prepared = ScheduledCharging.prepare(current, desired);
        try (RandomAccessFile file = new RandomAccessFile(new File(Platform.STATE, "lock"), "rw"); FileLock ignored = file.getChannel().lock()) {
            Config live = TweaksCommand.load();
            if (!live.enabled("chargeaway") || !homeKey.equals(live.get("homeLat") + "," + live.get("homeLon"))) return;
            attempts++; prepared.send();
        }
        status(distance + "Requested Scheduled Charging " + (wanted ? "on" : "off") + "; waiting for vehicle confirmation.");
    }
    private void status(String message) { if (!message.equals(lastStatus)) { lastStatus = message; Platform.status("chargeaway", message); } }
}
