package com.tilepad.tweakcore;

import java.io.*;
import java.nio.channels.FileLock;
import java.util.Properties;

/** Short root command used by the settings APK and uninstall; no listener process. */
public final class TweaksCommand {
    public static void main(String[] args) {
        try {
            Platform.initialize();
            try (RandomAccessFile file = new RandomAccessFile(new File(Platform.STATE, "lock"), "rw");
                 FileLock ignored = file.getChannel().lock()) {
                if (args.length == 1 && args[0].equals("summary")) {
                    // Launcher count: no APK hashing, vehicle calls, or patch recovery.
                    if (new File(Platform.STATE, "pending.properties").exists())
                        throw new IOException("Open Tweaks to finish the pending changes.");
                    Config summary = load(); int enabled = 0;
                    for (String id : Config.IDS) if (summary.enabled(id)) enabled++;
                    System.out.println("enabled=" + enabled + "\ntotal=" + Config.IDS.length);
                    return;
                }
                boolean restoring = args.length == 1 && args[0].equals("restore");
                // Recovery must still be possible when a pending enable can no longer
                // complete (e.g. a conflicting file). Restore supersedes that intent.
                if (!restoring) recover();
                Config c = load();
                Config previous = new Config((Properties) c.values.clone());
                // Settings reads configuration and small runtime files only. Compatibility
                // checks remain mandatory in the write transaction; status is the full audit.
                if (args.length == 1 && args[0].equals("settings")) { printStatus(c, false); return; }
                if (args.length == 1 && args[0].equals("status")) { printStatus(c, true); return; }
                if (args.length >= 3 && args.length % 2 == 1 && args[0].equals("options")) {
                    String[] pairs = new String[args.length - 1];
                    System.arraycopy(args, 1, pairs, 0, pairs.length);
                    printStatus(transaction().updateHeaterOptions(pairs), false);
                    return;
                }
                if (args.length == 1 && args[0].equals("restore")) {
                    for (String id : Config.IDS) c.set(id, "false");
                } else if (args.length >= 3 && args.length % 2 == 1 && (args[0].equals("set") || args[0].equals("update"))) {
                    for (int i = 1; i < args.length; i += 2) c.set(args[i], args[i + 1]);
                    // With the right-hand pop-over disabled the nav handle opens nothing, so it is always hidden.
                    if (c.enabled("popover")) c.set("nav", "true");
                    if (c.needsVehicleCheck(previous)) Platform.requireVehicle();
                    if (c.enabled("dst") && c.dstChanged(previous)) DstRules.baseOffsetMinutes(Platform.property("persist.sys.timezone"));
                } else throw new IllegalArgumentException("Usage: settings | summary | status | restore | set/update/options KEY VALUE [KEY VALUE…]");
                // Intent survives termination between a /system write and config commit.
                if (restoring) transaction().commitFull(c); else transaction().commit(c);
                if (args[0].equals("update")) { printStatus(c, false); return; }
                System.out.println("Saved. Static file changes take effect after a head-unit restart.");
            }
        } catch (Exception e) { System.err.println("Tweaks: " + e.getMessage()); System.exit(1); }
    }
    public static Config load() throws IOException { return transaction().load(); }
    public static void recover() throws Exception { transaction().recover(); }
    private static Transaction transaction() {
        return new Transaction(Platform.STATE, new Transaction.Apply() {
            @Override public void run(Config next, Config previous, boolean rollback) throws Exception {
                Platform.patches().apply(next, previous, Platform.boot());
                if (next.changedFrom(previous, "dst")) {
                    if (!next.enabled("dst")) DstController.restore();
                    else if (rollback) DstController.update(next, System.currentTimeMillis());
                }
            }
        });
    }
    private static void printStatus(Config c, boolean verify) throws Exception {
        Properties out = (Properties) c.values.clone();
        for (String key : new String[]{"region", "threshold", "driver", "wheel", "passenger", "driverLevel", "passengerLevel", "units"}) out.setProperty(key, c.get(key));
        String vehicleError = null;
        if (verify) try { Platform.requireVehicle(); } catch (Exception e) { vehicleError = e.getMessage(); }
        for (String id : Config.IDS) {
            out.setProperty(id, Boolean.toString(c.enabled(id)));
            String reason = "";
            try {
                if (verify) {
                    if (id.equals("dst") || id.equals("heaters") || id.equals("tracks") || id.equals("sync") || id.equals("driveronly")) {
                        if (vehicleError != null) throw new IOException(vehicleError);
                    }
                    if (id.equals("dst")) DstRules.baseOffsetMinutes(Platform.property("persist.sys.timezone"));
                    if (!id.equals("dst") && !id.equals("heaters") && !id.equals("sync") && !id.equals("driveronly")) Platform.patches().check(id, c);
                }
            } catch (Exception e) { reason = e.getMessage(); }
            out.setProperty("unavailable." + id, reason);
            File status = new File(Platform.STATE, "status-" + id);
            if (status.isFile()) out.setProperty("status." + id, new String(Files.read(status), "UTF-8"));
        }
        // pending.<id>: a file tweak whose checkbox differs from what this boot started with.
        try {
            Properties live = Files.properties(new File(Platform.STATE, "runtime.properties"));
            if (Platform.boot().equals(live.getProperty("boot")))
                for (String id : Config.FILE_IDS) {
                    String applied = live.getProperty("applied." + id);
                    out.setProperty("pending." + id, Boolean.toString(applied != null && !applied.equals(Boolean.toString(c.enabled(id)))));
                }
        } catch (Exception ignored) { }
        File restart = new File(Platform.STATE, "restart.boot");
        out.setProperty("restart", Boolean.toString(restart.isFile() && new String(Files.read(restart), "UTF-8").equals(Platform.boot())));
        String runtime = "";
        try {
            Properties live = Files.properties(new File(Platform.STATE, "runtime.properties"));
            String pid = live.getProperty("pid", "");
            if (!pid.matches("[0-9]+") || !Platform.boot().equals(live.getProperty("boot"))
                || !new String(Files.read(new File("/proc/" + pid + "/cmdline")), "UTF-8").contains("tilepad-longstar"))
                runtime = "Tweak listeners are not running. Restart the head unit or Tilepad’s longstar service.";
        } catch (Exception e) { runtime = "Tweak listeners are not running. Restart the head unit or Tilepad’s longstar service."; }
        out.setProperty("runtime", runtime);
        out.store(System.out, "Tilepad Tweaks status");
    }
    private TweaksCommand() { }
}
