package com.tilepad.longstar;

import com.tilepad.tweakcore.Platform;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
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
 * Reads the head unit's key device and starts Tilepad when the steering-wheel star button is held.
 * It never grabs the device or injects events. A hold isn't one of the car's own star options, so
 * during a qualifying hold it asks the car's mode service to skip the star action Button settings
 * assign (Home, for example), which the car would otherwise run on the release and cover Tilepad
 * with (StarHold). A short press is never touched.
 *
 * Usage: LongStarDaemon DEVICE_NAME KEY_CODE HOLD_MS COMPONENT [--device=PATH]
 */
public final class LongStarDaemon {
    private static final long RETRY_MS = 3000;
    /**
     * Exists while this daemon has the car's star action blocked, so a daemon restarted mid-hold
     * (or the uninstall hook) knows to allow it again, and never does so for a block it didn't make.
     */
    private static final File BLOCK_MARK = new File("/data/nemesis/backup/tilepad/star.blocked");
    // IModeManager on com.hkmc.micom.mode (Platform.keyAction and the rest), for the held key's type:
    // 5 for the steering-wheel star (SWRCCUSTOMKEY), 3 for the custom key (a Sonata's). disableKeyAction
    // makes ModeKeyDispatcher log "[swrc] Custom Key Action is blocked by app" and skip the action.
    // Every call goes through Platform's checks: the supported vehicle files and that release's
    // transaction codes, the service's interface descriptor and the reply.

    private final String deviceName;
    private final int keyCode, keyType;
    private final String component;
    private final String deviceOverride;
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
    private final StarHold hold;

    private LongStarDaemon(String deviceName, int keyCode, int keyType, long holdMs, String component, String deviceOverride) {
        this.deviceName = deviceName;
        this.keyCode = keyCode;
        this.keyType = keyType;
        this.component = component;
        this.deviceOverride = deviceOverride;
        this.hold = new StarHold(new CarHost(), holdMs,
            keyType == Evdev.CUSTOM_KEY_TYPE ? StarHold.CUSTOM_NONE_ACTION : StarHold.NONE_ACTION);
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
        // Shared listeners live in this process, alongside the key reader. Test input streams do
        // not bootstrap Android, touch tweak configuration or the car's star action.
        if (override == null) {
            com.tilepad.tweakcore.TweaksRuntime.start();
            checkFirmware();
        }
        int[] key = key(args[0], args[1]);
        new LongStarDaemon(args[0], key[0], key[1], Long.parseLong(args[2]), args[3], override).run();
    }

    /**
     * The scan code, a number as given or "auto" (the car's own custom key, from its key layout:
     * Evdev.customKey), and the key type whose assigned action a hold blocks (Evdev.keyType).
     */
    private static int[] key(String deviceName, String given) {
        int code = Evdev.STAR;
        try {
            if (!given.equals("auto")) code = Integer.parseInt(given);
            else {
                FileReader r = new FileReader(Evdev.layoutFor(deviceName));
                try { code = Evdev.customKey(r); } finally { r.close(); }
            }
            FileReader r = new FileReader(Evdev.layoutFor(deviceName));
            try { return new int[]{code, Evdev.keyType(r, code)}; } finally { r.close(); }
        } catch (IOException e) {
            log("no key layout for " + deviceName + " (" + e.getMessage() + "): using " + code + " as the steering-wheel star");
            return new int[]{code, Evdev.WHEEL_KEY_TYPE};
        }
    }

    private void run() {
        log("start device=\"" + deviceName + "\" code=" + keyCode + " type=" + keyType + " hold=" + hold.holdMs()
            + "ms target=" + component);
        // A restart mid-hold must never leave the car's star action blocked. Only a block this
        // daemon made (its mark is there) is undone at start.
        if (deviceOverride == null) hold.start();
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
                hold.deviceLost();
                String problem = (path == null ? "" : path + ": ") + e.getMessage();
                if (!problem.equals(lastProblem)) log("waiting: " + problem);  // don't spam the log
                lastProblem = problem;
                sleep(RETRY_MS);
            }
        }
    }

    /**
     * Checks the vehicle files now, in the background, rather than at the first hold, whose mode
     * service calls need them; says so if this firmware's star action can't be blocked.
     */
    private static void checkFirmware() {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try { Platform.requireVehicle(); }
                catch (Exception e) { log("hold: the car's star action can't be blocked on this firmware, so Tilepad opens after the release: " + e.getMessage()); }
            }
        }, "tilepad-star-check");
        t.setDaemon(true);
        t.start();
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
                    hold.onKey(Evdev.value(ev, 0));
                }
            }
        } finally {
            in.close();
        }
    }

    /** The car side of StarHold: the mode service through Platform, the timer, the mark and Tilepad. */
    private final class CarHost implements StarHold.Host {
        @Override public String action() throws Exception {
            if (deviceOverride != null) throw new IOException("test input");
            return Platform.keyAction(keyType);
        }
        @Override public void disable(String[] actions) throws Exception {
            Platform.disableKeyAction(keyType, actions);
        }
        @Override public void enable() throws Exception {
            Platform.enableKeyAction(keyType);
        }
        @Override public StarHold.Task after(long ms, Runnable r) {
            final ScheduledFuture<?> task = timer.schedule(r, ms, TimeUnit.MILLISECONDS);
            return new StarHold.Task() {
                @Override public void cancel() { task.cancel(false); }
            };
        }
        @Override public boolean marked() { return BLOCK_MARK.exists(); }
        @Override public void mark(boolean blocked) {
            if (!blocked) {
                if (BLOCK_MARK.exists() && !BLOCK_MARK.delete()) log("couldn't remove " + BLOCK_MARK);
                return;
            }
            try {
                File dir = BLOCK_MARK.getParentFile();
                if (dir != null) dir.mkdirs();
                // "<enableKeyAction code> <key type>": what the uninstall hook's `service call` needs,
                // since the code differs between releases.
                FileWriter w = new FileWriter(BLOCK_MARK);
                try { w.write(Platform.enableKeyActionCode() + " " + keyType + "\n"); } finally { w.close(); }
            } catch (Exception e) {
                log("couldn't create " + BLOCK_MARK + ": " + e.getMessage());
            }
        }
        @Override public void open() { launch(); }
        @Override public long now() { return LongStarDaemon.now(); }
        @Override public void log(String line) { LongStarDaemon.log(line); }
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
