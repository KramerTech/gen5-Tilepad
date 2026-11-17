package com.tilepad.tweakcore;

import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.provider.Settings;
import android.content.Context;
import java.io.*;
import java.util.*;

/** Root-only OEM access, gated on the exact captured vehicle API implementation. */
public final class Platform {
    public static final File STATE = new File("/data/nemesis/backup/tilepad-tweaks");
    public static final String HOME = "/data/nemesis/apps/tilepad";
    /** A readable copy of each tweak's status, for the Tweaks screen to refresh live (STATE is root-only). */
    public static final File LIVE = new File("/data/nemesis/backup/tilepad/status");
    private static Firmware.Release vehicle;
    /** A call that wasn't sent: the firmware's code for it isn't the one Tilepad checked. */
    public static final class NotSent extends IOException {
        NotSent(String message) { super(message); }
    }
    private Platform() { }
    /**
     * Checks confirmed against the firmware's own Stub constant for descriptor's method and refuses
     * with NotSent on mismatch. Only read calls may opt in via firmwareOwn when confirmed is 0.
     */
    static int transaction(String descriptor, String method, int confirmed, boolean firmwareOwn) throws Exception {
        requireVehicle();
        int code = Transactions.decide(confirmed, Transactions.named(descriptor, method), firmwareOwn);
        if (code == 0) throw new NotSent("This firmware's version of that request isn't the one Tilepad checked, so Tilepad didn't send it. Tap “Save Log to USB” in Support and submit the log to help add compatibility.");
        return code;
    }
    public static void requireVehicle() throws Exception { vehicle(); }
    /** The supported release this unit's vehicle interfaces belong to. */
    static Firmware.Release vehicle() throws Exception {
        if (vehicle != null) return vehicle;
        // One firmware image serves several models; the car's name is set at boot. Identical vehicle
        // framework files mean identical vehicle interfaces, whichever car the unit is in.
        Firmware.Release found = Build.VERSION.SDK_INT != 19 ? null : Firmware.vehicleRelease(
            Files.hash(new File("/system/framework/automotive.odex")), Files.hash(new File("/system/framework/automotive-service.odex")));
        if (found == null)
            throw new IOException("This firmware's vehicle interface isn't supported yet. Capture and submit a log to help add compatibility.");
        return vehicle = found;
    }
    /** Climate Sync and Driver Only need dual-zone climate: the IONIQ 5's, which the EV6 and IONIQ 6 share (same DATC variant and options). */
    public static void requireDualZone() throws Exception {
        requireVehicle();
        if (!Build.DEVICE.startsWith("wp_daudioplus_neev") && !Build.DEVICE.startsWith("wp_daudioplus_cvev")
                && !Build.DEVICE.startsWith("wp_daudioplus_ceev"))
            throw new IOException("This car's climate isn't supported yet. Capture and submit a log to help add compatibility.");
    }
    public static String property(String name) throws Exception {
        return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, name);
    }
    /**
     * Whether the car can be plugged in, by HEVService's own tests: checkEVModel (the
     * com.hkmc.hardware.ev2 or .ev feature) or checkPHEVModel (.phev). Gas cars and plain hybrids
     * have neither. True where com.hkmc.Feature can't be read, so a tweak is never hidden by mistake.
     */
    public static boolean pluginVehicle() {
        try {
            java.lang.reflect.Method has = Class.forName("com.hkmc.Feature").getMethod("hasFeature", String.class);
            for (String feature : new String[]{"com.hkmc.hardware.ev2", "com.hkmc.hardware.ev", "com.hkmc.hardware.phev"})
                if ((Boolean) has.invoke(null, feature)) return true;
            return false;
        } catch (Exception e) { return true; }
    }
    /** Ignition from the BCM (ACC reads off): Driver Only, Automatic Heaters' passenger seat and projection. */
    public static boolean ignitionOn() throws Exception { return integer("com.hkmc.micom.bcm", "com.hkmc.bcm.IBCMService", 0x11) != 0; }
    public static String boot() throws IOException { return new String(Files.read(new File("/proc/sys/kernel/random/boot_id")), "UTF-8").trim(); }
    public static int integer(String service, String descriptor, int transaction, int... args) throws Exception {
        return transact(service, descriptor, transaction, true, args);
    }
    public static void command(String service, String descriptor, int transaction, int... values) throws Exception {
        transact(service, descriptor, transaction, false, values);
    }
    /** A read taking ints and returning a string, which may be null (IModeManager.getKeyAction). */
    public static String string(String service, String descriptor, int transaction, int... args) throws Exception {
        IBinder binder = service(service, descriptor);
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor); for (int n : args) data.writeInt(n);
            if (!binder.transact(transaction, data, reply, 0)) throw new IOException("OEM request refused");
            reply.readException();
            String value = reply.readString();
            if (reply.dataAvail() != 0) throw new IOException("Unexpected OEM reply");
            return value;
        } finally { data.recycle(); reply.recycle(); }
    }
    /** A command taking an int and a string array and returning nothing (IModeManager.disableKeyAction). */
    public static void command(String service, String descriptor, int transaction, int value, String[] strings) throws Exception {
        IBinder binder = service(service, descriptor);
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor); data.writeInt(value); data.writeStringArray(strings);
            if (!binder.transact(transaction, data, reply, 0)) throw new IOException("OEM request refused");
            reply.readException();
            if (reply.dataAvail() != 0) throw new IOException("Unexpected OEM reply");
        } finally { data.recycle(); reply.recycle(); }
    }
    /**
     * IModeManager on com.hkmc.micom.mode, for a key's assigned action (key type 3, the custom key;
     * 5, the steering-wheel ★), with the codes of the supported release this unit runs.
     */
    private static final String MODE = "com.hkmc.micom.mode", MODE_IFACE = "com.hkmc.mode.IModeManager";
    public static String keyAction(int keyType) throws Exception {
        return string(MODE, MODE_IFACE, vehicle().mode.keyAction, keyType);
    }
    public static void disableKeyAction(int keyType, String[] actions) throws Exception {
        command(MODE, MODE_IFACE, vehicle().mode.disableKeyAction, keyType, actions);
    }
    public static void enableKeyAction(int keyType) throws Exception {
        command(MODE, MODE_IFACE, vehicle().mode.enableKeyAction, keyType);
    }
    /** enableKeyAction's code, for the uninstall hook's `service call` (star.blocked). */
    public static int enableKeyActionCode() throws Exception { return vehicle().mode.enableKeyAction; }
    /** register*Listener(listener, token) taking two strong binders and returning nothing. Returns the service. */
    public static IBinder register(String service, String descriptor, int transaction, IBinder listener, IBinder token) throws Exception {
        IBinder binder = service(service, descriptor);
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor); data.writeStrongBinder(listener); data.writeStrongBinder(token);
            if (!binder.transact(transaction, data, reply, 0)) throw new IOException("OEM request refused");
            reply.readException();
            return binder;
        } finally { data.recycle(); reply.recycle(); }
    }
    static IBinder service(String name, String descriptor) throws Exception {
        requireVehicle();
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, name);
        if (binder == null || !binder.isBinderAlive() || !descriptor.equals(binder.getInterfaceDescriptor())) throw new IOException("OEM service unavailable: " + name);
        return binder;
    }
    private static int transact(String name, String descriptor, int transaction, boolean returnsInt, int... args) throws Exception {
        IBinder binder = service(name, descriptor);
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor); for (int n : args) data.writeInt(n);
            if (!binder.transact(transaction, data, reply, 0)) throw new IOException("OEM request refused");
            reply.readException();
            if (reply.dataAvail() != (returnsInt ? 4 : 0)) throw new IOException("Unexpected OEM reply");
            return returnsInt ? reply.readInt() : 0;
        } finally { data.recycle(); reply.recycle(); }
    }
    public static String exec(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] b = new byte[4096]; for (int n; (n = in.read(b)) != -1;) if (out.size() < 131072) out.write(b, 0, n);
        }
        int code = p.waitFor();
        String text = out.toString("UTF-8").trim();
        if (code != 0) throw new IOException("Command failed (" + code + "): " + text);
        return text;
    }
    public static void initialize() throws Exception {
        if (!STATE.isDirectory() && !STATE.mkdirs()) throw new IOException("Cannot create tweak state");
        exec("chmod", "0700", STATE.getPath());
    }
    public static void status(String id, String message) {
        try { Files.write(new File(STATE, "status-" + id), message.getBytes("UTF-8")); }
        catch (IOException e) { System.err.println("tweaks: " + e); }
        try {
            File live = new File(LIVE, id);
            Files.write(live, message.getBytes("UTF-8"));
            LIVE.setReadable(true, false); LIVE.setExecutable(true, false); live.setReadable(true, false);
        } catch (IOException e) { System.err.println("tweaks: " + e); }
        System.out.println("tweaks " + id + ": " + message);
    }
    public static PatchManager patches() {
        String override = System.getenv("TILEPAD_TWEAK_RECIPES");
        File recipes = override == null ? new File(HOME, "tweaks/patches") : new File(override);
        return new PatchManager(STATE, recipes, new PatchManager.SystemOps() {
            private boolean remounted;
            @Override public File file(String path) { return new File(path); }
            @Override public void writable() throws Exception {
                String mounts = new String(Files.read(new File("/proc/mounts")), "UTF-8");
                for (String line : mounts.split("\n")) {
                    String[] fields = line.split(" ");
                    if (fields.length > 3 && fields[1].equals("/system")) remounted = fields[3].startsWith("ro,") || fields[3].equals("ro");
                }
                if (remounted) exec("/system/xbin/nemesis", "remount", "rw");
            }
            @Override public List<String> layouts() throws Exception { return PatchManager.loadedLayouts(exec("dumpsys", "input")); }
            @Override public void finish() throws Exception { if (remounted) exec("/system/xbin/nemesis", "remount", "ro"); }
            @Override public void replace(File target, byte[] bytes) throws Exception {
                // Write and sync on the same filesystem, establish normal system-file
                // ownership/mode before atomic rename. Stock copies stay outside /system.
                File temp = new File(target.getPath() + ".tilepad-new");
                Files.write(temp, bytes);
                exec("chown", "0:0", temp.getPath()); exec("chmod", "0644", temp.getPath());
                if (!temp.renameTo(target)) throw new IOException("Cannot replace " + target);
                exec("sync");
            }
        });
    }
}
