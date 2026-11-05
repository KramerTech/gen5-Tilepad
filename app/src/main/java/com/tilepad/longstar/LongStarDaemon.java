package com.tilepad.longstar;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Root daemon started by the NEMESIS service (svc/longstar.sh) through app_process.
 * Passively reads the head unit's key device and starts Tilepad when the steering-wheel
 * star button is held. It never grabs the device or injects events, so the stock handling
 * of every key, including the star, is unchanged.
 *
 * Usage: LongStarDaemon DEVICE_NAME KEY_CODE HOLD_MS COMPONENT [--device=PATH]
 */
public final class LongStarDaemon {
    private static final long COOLDOWN_MS = 1500;
    private static final long RETRY_MS = 3000;

    private final String deviceName;
    private final int keyCode;
    private final String component;
    private final String deviceOverride;
    private final HoldDetector detector;
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
    private ScheduledFuture<?> pending;

    private LongStarDaemon(String deviceName, int keyCode, long holdMs, String component, String deviceOverride) {
        this.deviceName = deviceName;
        this.keyCode = keyCode;
        this.component = component;
        this.deviceOverride = deviceOverride;
        this.detector = new HoldDetector(holdMs, COOLDOWN_MS);
    }

    public static void main(String[] args) {
        if (args.length < 4) {
            System.err.println("usage: LongStarDaemon DEVICE_NAME KEY_CODE HOLD_MS COMPONENT [--device=PATH]");
            System.exit(2);
        }
        String override = null;
        for (int i = 4; i < args.length; i++) {
            if (args[i].startsWith("--device=")) override = args[i].substring(9);
        }
        // Shared listeners live in this process, alongside the passive key reader.
        // Test input streams do not bootstrap Android or touch tweak configuration.
        if (override == null) com.tilepad.tweakcore.TweaksRuntime.start();
        new LongStarDaemon(args[0], Integer.parseInt(args[1]), Long.parseLong(args[2]), args[3], override).run();
    }

    private void run() {
        log("start device=\"" + deviceName + "\" code=" + keyCode + " hold=" + detector.holdMs()
            + "ms target=" + component);
        String lastProblem = null;
        while (true) {
            String path = null;
            try {
                path = deviceOverride != null ? deviceOverride : locate();
                if (path == null) throw new IOException("device not found");
                lastProblem = null;
                log("reading " + path);
                read(path);
                if (deviceOverride != null) {
                    // Test mode: let an already-armed timer finish, then exit.
                    log("input ended");
                    timer.shutdown();
                    try { timer.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                    return;
                }
                throw new IOException("end of stream");
            } catch (IOException e) {
                synchronized (this) { cancelPending(); detector.reset(); }
                String problem = (path == null ? "" : path + ": ") + e.getMessage();
                if (!problem.equals(lastProblem)) log("waiting: " + problem);  // don't spam the log
                lastProblem = problem;
                sleep(RETRY_MS);
            }
        }
    }

    private String locate() throws IOException {
        FileReader r = new FileReader("/proc/bus/input/devices");
        try {
            return Evdev.findDevice(r, deviceName);
        } finally {
            r.close();
        }
    }

    private void read(String path) throws IOException {
        InputStream raw = "-".equals(path) ? System.in : new FileInputStream(path);
        DataInputStream in = new DataInputStream(raw);
        byte[] ev = new byte[Evdev.EVENT_SIZE];
        try {
            while (true) {
                try {
                    in.readFully(ev);
                } catch (java.io.EOFException e) {
                    return;
                }
                if (Evdev.type(ev, 0) == Evdev.EV_KEY && Evdev.code(ev, 0) == keyCode) {
                    onKey(Evdev.value(ev, 0));
                }
            }
        } finally {
            in.close();
        }
    }

    private synchronized void onKey(int value) {
        int action = detector.onKey(value, now());
        if (action == HoldDetector.ARM) {
            cancelPending();
            final int generation = detector.generation();
            pending = timer.schedule(new Runnable() {
                @Override public void run() { onTimer(generation); }
            }, detector.holdMs(), TimeUnit.MILLISECONDS);
        } else if (action == HoldDetector.DISARM) {
            cancelPending();
        }
    }

    private void onTimer(int generation) {
        synchronized (this) {
            if (!detector.onTimer(generation, now())) return;
        }
        launch();
    }

    private void cancelPending() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
    }

    private void launch() {
        long started = now();
        try {
            int rc = startDirect();
            log("hold -> start result=" + rc + " in " + (now() - started) + "ms");
            if (rc >= 0) return;  // ActivityManager.START_SUCCESS and the other non-error results
        } catch (Throwable t) {
            log("hold -> direct start failed, using am: " + t);
        }
        try {
            Process p = new ProcessBuilder("am", "start", "-n", component,
                "-f", "0x10000000", "--ez", "from_knob", "true")
                .redirectErrorStream(true).start();
            byte[] buf = new byte[512];
            StringBuilder out = new StringBuilder();
            InputStream s = p.getInputStream();
            for (int n; (n = s.read(buf)) > 0; ) {
                if (out.length() < 2048) out.append(new String(buf, 0, n, "UTF-8"));
            }
            int rc = p.waitFor();
            String text = out.toString().trim().replace('\n', ' ');
            log("hold -> am start rc=" + rc + " in " + (now() - started) + "ms" + (text.contains("Error") ? " " + text : ""));
        } catch (IOException | InterruptedException e) {
            log("hold -> launch failed: " + e);
        }
    }

    /**
     * What `am start -n COMPONENT -f 0x10000000 --ez from_knob true` does, from this process:
     * IActivityManager.startActivityAsUser with no caller app. Spawning `am` took ~640 ms on the
     * car, most of the delay after the hold.
     */
    private int startDirect() throws Exception {
        android.content.Intent intent = new android.content.Intent()
            .setComponent(android.content.ComponentName.unflattenFromString(component))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("from_knob", true);
        Object am = Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
        java.lang.reflect.Method start = Class.forName("android.app.IActivityManager").getMethod("startActivityAsUser",
            Class.forName("android.app.IApplicationThread"), String.class, android.content.Intent.class, String.class,
            android.os.IBinder.class, String.class, int.class, int.class, String.class,
            android.os.ParcelFileDescriptor.class, android.os.Bundle.class, int.class);
        return (Integer) start.invoke(am, null, null, intent, null, null, null, 0, 0, null, null, null, 0);
    }

    private static long now() { return System.nanoTime() / 1000000L; }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private static void log(String msg) {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        System.out.println(ts + " longstar " + msg);
        System.out.flush();
    }
}
