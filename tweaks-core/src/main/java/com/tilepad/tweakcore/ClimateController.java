package com.tilepad.tweakcore;

import android.os.*;
import java.io.File;
import java.lang.reflect.Method;

/**
 * Maintain Climate Sync and Automatic Driver Only on the car, on the tweaks looper. Climate changes arrive as
 * DATC listener callbacks. Ignition and the passenger belt have no working callback on this car,
 * so Automatic Driver Only reads them once a second by in-process Binder calls. Each time the car is
 * turned on it starts over; a short stop doesn't restart the head unit, so a boot isn't enough.
 */
final class ClimateController {
    private static final String DATC = "com.hkmc.micom.datc", DATC_IFACE = "com.hkmc.datc.IDATCService";
    private static final String DATC_LISTENER = "com.hkmc.datc.IDATCListener";
    private static final int DATC_REGISTER = 1, DATC_UNREGISTER = 2, DATC_SET_FRONT = 65;
    // onDatcStatus carries the Sync/Driver Only flags; set point changes arrive only as
    // onTemperatureInformation (seen on the car: a status alone keeps the old temperatures).
    private static final int ON_TEMPERATURE = 1, ON_DATC_STATUS = 3;
    private static final String HSCAN = "tm_hscan_service", HSCAN_IFACE = "com.hkmc.telematics.hscan.tm_interface.ITM_HSCANService";
    private static final int HSCAN_PASSENGER_BELT = 0xcd;
    // The DATC reports a passenger press as a temperature and a status ~10 ms apart; act once
    // both are in, and on our own request's reply straight away. Further presses are just
    // followed by the next steps. With no reply within a second the state is re-read from the
    // service rather than assumed, and a request that changes nothing is retried at most twice.
    private static final long SETTLE_MS = 40, REPLY_SETTLE_MS = 0, REPLY_MS = 1000, POLL_MS = 1000;
    private static final int MAX_UNCHANGED = 3, MAX_STEPS = 40;
    private static final String DRIVER_ONLY_SET = "Driver Only is on for this drive. The passenger seat belt is being watched, and buckling it switches to Sync.";
    private static final String WAITING_CAR = "Waiting for the car and the climate to be on. Driver Only is set then, and the passenger seat belt is watched.";
    private static final String SYNCED = "The passenger seat belt was buckled, so Sync is on for the rest of this drive. Driver Only will be set again next time the car is turned on.";

    private final Handler handler;
    private final File autoFile = new File(Platform.STATE, "driveronly.boot");
    private boolean sync, auto, belted;
    private Boolean ignition;
    private ClimatePolicy.AlwaysSync always;
    private ClimatePolicy.Climate climate;
    private int stage, unchanged, steps;
    private String lastSent;
    private boolean awaiting;
    private Float driverC, passengerC;
    private IBinder datc;
    private final Binder token = new Binder(), listener = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(DATC_LISTENER);
            if (code == ON_DATC_STATUS && data.readInt() != 0) {
                final ClimatePolicy.Climate c = read(data);
                if (c != null) handler.post(new Runnable() { @Override public void run() { onClimate(c); } });
            } else if (code == ON_TEMPERATURE) {
                // (drC, state, drF, state, psC, state, psF, state, ambient…, rear…)
                final float driver = data.readFloat(); data.readInt(); data.readFloat(); data.readInt();
                final float passenger = data.readFloat();
                handler.post(new Runnable() { @Override public void run() { onTemperatures(driver, passenger); } });
            }
            if ((flags & FLAG_ONEWAY) == 0 && reply != null) reply.writeNoException();
            return true;
        }
    };
    private final IBinder.DeathRecipient datcDied = new IBinder.DeathRecipient() {
        @Override public void binderDied() { handler.post(new Runnable() { @Override public void run() { datc = null; if (running()) handler.postDelayed(connect, 5000); } }); }
    };
    private final Runnable poll = new Runnable() { @Override public void run() { pollCar(); } };
    private final Runnable evaluate = new Runnable() { @Override public void run() { evaluate(); } };
    private final Runnable connect = new Runnable() { @Override public void run() { connect(); } };

    ClimateController(Handler handler) { this.handler = handler; }
    private boolean running() { return sync || auto; }

    void configure(boolean alwaysSync, boolean autoDriverOnly) {
        boolean was = running();
        if (alwaysSync && !sync) {
            always = new ClimatePolicy.AlwaysSync(); pauseShown = false;
            if (climate != null) { always.observe(climate, SystemClock.uptimeMillis()); always.next(climate, SystemClock.uptimeMillis()); }
            syncProblem = null; syncShown = null;
        }
        if (!alwaysSync && sync) { Platform.status("sync", ""); syncShown = null; }
        sync = alwaysSync;
        showSync();
        if (autoDriverOnly && !auto) {
            // The saved stage carries a restart of this service through the same drive; it is
            // reset to new whenever the car is seen off.
            stage = ClimatePolicy.STAGE_NEW; ignition = null;
            try {
                String[] saved = new String(Files.read(autoFile), "UTF-8").split(" ");
                if (saved.length == 2 && saved[0].equals(Platform.boot())) stage = Integer.parseInt(saved[1]);
            } catch (Exception ignored) { }
            belted = stage >= ClimatePolicy.STAGE_SYNC;
            Platform.status("driveronly", belted ? SYNCED
                : stage == ClimatePolicy.STAGE_DRIVER_ONLY ? DRIVER_ONLY_SET
                : WAITING_CAR);
            handler.removeCallbacks(poll); handler.post(poll);
        }
        if (!autoDriverOnly && auto) { handler.removeCallbacks(poll); Platform.status("driveronly", ""); }
        auto = autoDriverOnly;
        if (running() && !was) { unchanged = 0; connect(); }
        if (!running() && was) disconnect();
        if (running()) schedule(SETTLE_MS);
    }

    private void disconnect() {
        handler.removeCallbacks(evaluate); handler.removeCallbacks(connect);
        climate = null; driverC = passengerC = null; awaiting = false;
        if (datc == null) return;
        try { datc.unlinkToDeath(datcDied, 0); Platform.register(DATC, DATC_IFACE, DATC_UNREGISTER, listener, token); } catch (Exception ignored) { }
        datc = null;
    }

    private void connect() {
        if (!running() || datc != null) return;
        try {
            datc = Platform.register(DATC, DATC_IFACE, DATC_REGISTER, listener, token);
            datc.linkToDeath(datcDied, 0);
            // The listener reports changes; take the current state from the service's own dump.
            ClimatePolicy.Climate now = ClimatePolicy.parseDump(Platform.exec("dumpsys", DATC));
            if (now != null && climate == null) onClimate(now);
            syncProblem = null; showSync();
        } catch (Exception e) {
            datc = null;
            String message = "The climate system isn't ready yet; trying again every few seconds. (" + e.getMessage() + ")";
            if (sync) { syncProblem = message; showSync(); }
            if (auto) Platform.status("driveronly", message);
            handler.postDelayed(connect, 5000);
        }
    }

    private void pollCar() {
        if (!auto) return;
        try {
            boolean on = Platform.ignitionOn();
            if (!on && !Boolean.FALSE.equals(ignition)) {
                // The drive is over: the next car start is a new one, even across a restart of this service.
                stage = ClimatePolicy.STAGE_NEW; belted = false; saveStage();
                Platform.status("driveronly", "The car is off. Driver Only will be set again when the car is next turned on.");
            } else if (on && Boolean.FALSE.equals(ignition)) {
                Platform.status("driveronly", "The car is on. Driver Only will be set as soon as the climate is on, and the passenger seat belt is being watched.");
                schedule(0);
            }
            ignition = on;
            if (on && !belted && passengerBuckled()) {
                belted = true;
                Platform.status("driveronly", "The passenger seat belt was buckled, so the climate is switching to Sync for the rest of the drive.");
                schedule(0);
            }
        } catch (Exception e) {
            Platform.status("driveronly", "The car's ignition or seat belt couldn't be read, so nothing was changed. (" + e.getMessage() + ")");
        }
        handler.postDelayed(poll, POLL_MS);
    }
    /** The front passenger's seat belt switch; occupancy reads 0 on this car, so this is the presence signal. */
    static boolean passengerBuckled() throws Exception { return Platform.integer(HSCAN, HSCAN_IFACE, HSCAN_PASSENGER_BELT) == 1; }
    private void saveStage() {
        try { Files.write(autoFile, (Platform.boot() + " " + stage).getBytes("UTF-8")); } catch (Exception ignored) { }
    }

    private void onClimate(ClimatePolicy.Climate c) {
        // Once temperature callbacks have arrived they are the current set points.
        if (driverC != null) c = new ClimatePolicy.Climate(c.on, c.sync, c.driverOnly, driverC, passengerC);
        trace("status", c);
        update(c);
    }
    private void onTemperatures(float driver, float passenger) {
        driverC = driver; passengerC = passenger;
        if (climate == null) return;
        ClimatePolicy.Climate c = new ClimatePolicy.Climate(climate.on, climate.sync, climate.driverOnly, driver, passenger);
        trace("temps", c);
        update(c);
    }
    private String traced;
    /** Each changed reading and where it came from, in the service log, so the order the car reports things in can be checked. */
    private void trace(String source, ClimatePolicy.Climate c) {
        String text = c.toString();
        if (text.equals(traced)) return;
        traced = text;
        System.out.println("climate " + SystemClock.uptimeMillis() + " " + source + " " + text);
    }
    private boolean pauseShown;
    private void reportPause() {
        if (always.paused() == pauseShown) return;
        pauseShown = always.paused();
        System.out.println("climate " + SystemClock.uptimeMillis() + (pauseShown ? " paused" : " resumed") + " on " + climate);
        showSync();
    }
    private String syncProblem, syncShown;
    /** Maintain Climate Sync's mode, or the problem it last hit until the climate changes. */
    private void showSync() {
        if (!sync) return;
        String message = syncProblem != null ? syncProblem
            : climate == null ? "Waiting for the climate system."
            : !climate.on ? "Waiting for the climate to be turned on."
            : always.paused() ? "Manual: 'Sync' was turned off in the climate settings, so the two sides are set separately until 'Sync' is pressed again."
            : climate.driverOnly ? "Paused while Driver Only is on."
            // Sync off but not paused is momentary: this tweak is turning it back on.
            : "Auto sync: the passenger temperature follows the driver's.";
        if (message.equals(syncShown)) return;
        syncShown = message;
        Platform.status("sync", message);
    }
    private void update(ClimatePolicy.Climate c) {
        if (climate == null || !climate.toString().equals(c.toString())) { unchanged = 0; syncProblem = null; }
        climate = c;
        if (sync) { always.observe(c, SystemClock.uptimeMillis()); reportPause(); showSync(); }
        schedule(awaiting ? REPLY_SETTLE_MS : SETTLE_MS);
        awaiting = false;
    }

    private void schedule(long delay) { handler.removeCallbacks(evaluate); handler.postDelayed(evaluate, delay); }

    private void evaluate() {
        if (awaiting) {
            // Our request got no callback: act on the service's real state, never a stale guess.
            awaiting = false;
            try {
                ClimatePolicy.Climate now = ClimatePolicy.parseDump(Platform.exec("dumpsys", DATC));
                if (now != null) {
                    driverC = now.driver; passengerC = now.passenger;
                    trace("dump", now);
                    if (climate == null || !climate.toString().equals(now.toString())) unchanged = 0;
                    climate = now;
                    if (sync) { always.observe(now, SystemClock.uptimeMillis()); reportPause(); showSync(); }
                }
            } catch (Exception ignored) { }
        }
        if (!running() || climate == null || !climate.on) return;
        int action = ClimatePolicy.NONE;
        if (auto && Boolean.TRUE.equals(ignition)) {
            int target = ClimatePolicy.autoTarget(stage, belted);
            int reached = ClimatePolicy.autoStage(stage, target, climate);
            if (reached != stage) {
                stage = reached;
                saveStage();
                Platform.status("driveronly", stage == ClimatePolicy.STAGE_SYNC ? SYNCED : DRIVER_ONLY_SET);
                target = ClimatePolicy.autoTarget(stage, belted);
            }
            action = target;
        }
        // Maintain Climate Sync also tracks the settled passenger set point while Automatic Driver Only acts.
        int lock = sync ? always.next(climate, SystemClock.uptimeMillis()) : ClimatePolicy.NONE;
        if (sync) { reportPause(); showSync(); }  // a pause is confirmed here, once no passenger change followed the drop
        if (action == ClimatePolicy.NONE) action = lock;
        if (action == ClimatePolicy.NONE) {
            unchanged = 0; steps = 0; lastSent = null;
            // Waiting on our step, or caught up while the passenger side is still being pressed.
            if (sync && climate.unsynced()) schedule(Math.max(0, always.wakeAt() - SystemClock.uptimeMillis()));
            return;
        }
        String request = action + " " + climate;
        if (request.equals(lastSent) && ++unchanged >= MAX_UNCHANGED) {
            if (unchanged == MAX_UNCHANGED) report(action, "The climate system didn't respond to the last change. Trying again after the next climate change. (" + climate + ")");
            return;
        }
        if (action == ClimatePolicy.DRIVER_UP || action == ClimatePolicy.DRIVER_DOWN) {
            if (++steps > MAX_STEPS) { report(action, "Matching the driver temperature stopped after " + MAX_STEPS + " steps without catching up. (" + climate + ")"); return; }
        } else steps = 0;
        lastSent = request;
        try {
            System.out.println("climate " + SystemClock.uptimeMillis() + " send " + action + " on " + climate);
            for (int[] command : ClimatePolicy.commands(climate, action)) Platform.command(DATC, DATC_IFACE, DATC_SET_FRONT, command[0], command[1]);
            if (sync) always.sent(action, SystemClock.uptimeMillis());
            if (action == ClimatePolicy.SYNC && lock == ClimatePolicy.SYNC) { syncProblem = null; showSync(); }
        } catch (Exception e) {
            report(action, "The climate couldn't be changed. (" + e.getMessage() + ")");
        }
        awaiting = true;
        schedule(REPLY_MS);
    }

    private void report(int action, String message) {
        boolean automatic = action == ClimatePolicy.DRIVER_ONLY || (action == ClimatePolicy.SYNC && auto && belted && stage < ClimatePolicy.STAGE_SYNC);
        if (automatic) Platform.status("driveronly", message);
        else { syncProblem = message; showSync(); }
    }

    /** com.hkmc.datc.DatcStatus from automotive.jar, the same class the stock climate app reads. */
    private static ClimatePolicy.Climate read(Parcel data) {
        try {
            Class<?> type = Class.forName("com.hkmc.datc.DatcStatus");
            Object status = ((Parcelable.Creator<?>) type.getField("CREATOR").get(null)).createFromParcel(data);
            return new ClimatePolicy.Climate(number(type, status, "getOperationStatus") == 1, number(type, status, "getSyncDisp") == 1,
                number(type, status, "getDriverOnly") == 1, decimal(type, status, "getDrTempC"), decimal(type, status, "getPsTempC"));
        } catch (Throwable e) {
            Platform.status("sync", "A climate update couldn't be read. (" + e + ")");
            return null;
        }
    }
    private static int number(Class<?> type, Object o, String getter) throws Exception {
        Method m = type.getMethod(getter); return ((Number) m.invoke(o)).intValue();
    }
    private static float decimal(Class<?> type, Object o, String getter) throws Exception {
        Method m = type.getMethod(getter); return ((Number) m.invoke(o)).floatValue();
    }
}
