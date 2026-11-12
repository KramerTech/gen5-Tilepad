package com.tilepad.tweakcore;

import java.io.*;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.List;
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
                    // Whether this boot started with the nav handle hidden, so the launcher can
                    // drop the inset that keeps tiles clear of it.
                    try {
                        Properties live = Files.properties(new File(Platform.STATE, "runtime.properties"));
                        if (Platform.boot().equals(live.getProperty("boot")))
                            System.out.println("handleHidden=" + "true".equals(live.getProperty("applied.nav")));
                    } catch (Exception unknown) { }
                    return;
                }
                // Tweaks › Package Manager's updates, downloaded by the app: checked and started without touching tweak state.
                if (args.length >= 5 && (args.length - 1) % 4 == 0 && args[0].equals("self-update")) {
                    System.out.println("installing=" + selfUpdate(args));
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
                // After an update: rewrite owned files with this release's recipes (one may have changed)
                // and the saved settings. Postinstall runs it, before the restart an install needs anyway.
                if (args.length == 1 && args[0].equals("reconcile")) {
                    Platform.patches().apply(c, null, Platform.boot());
                    System.out.println("Owned files match this release's recipes.");
                    return;
                }
                if (args.length == 1 && args[0].equals("status")) { printStatus(c, true); return; }
                // Package Manager's other software: a package and the GitHub repository whose releases update it.
                if ((args.length == 4 && args[0].equals("update-source") && args[1].equals("add"))
                    || (args.length == 3 && args[0].equals("update-source") && args[1].equals("remove"))) {
                    SelfUpdate.setSource(SelfUpdate.SOURCES, SelfUpdate.APPS, SelfUpdate.PACKAGES, args[2], args.length == 4 ? args[3] : null);
                    printStatus(c, false); return;
                }
                // Package Manager's delete: NEMESIS uninstalls the package, then its link goes.
                if (args.length == 2 && args[0].equals("uninstall")) {
                    String id = args[1];
                    if (SelfUpdate.installing(SelfUpdate.DIR, Platform.boot(), System.currentTimeMillis()))
                        throw new IOException("An update is installing. The head unit restarts when it's done; uninstall after that.");
                    if (SelfUpdate.uninstallable(SelfUpdate.PACKAGES, id)) {
                        String said = "";
                        try { said = Platform.exec("/system/xbin/nemesis", "uninstall", id); } catch (IOException e) { said = e.getMessage(); }
                        // Its records say whether it went, whatever the exit status.
                        if (SelfUpdate.uninstallable(SelfUpdate.PACKAGES, id)) throw new IOException("NEMESIS didn't uninstall it" + (said.isEmpty() ? "." : ": " + said));
                    }
                    SelfUpdate.setSource(SelfUpdate.SOURCES, SelfUpdate.APPS, SelfUpdate.PACKAGES, id, null);
                    printStatus(c, false); return;
                }
                if (args.length == 1 && (args[0].equals("report") || args[0].equals("support"))) {
                    System.out.println("saved=" + UsbReport.save(status(c, true), c, args[0].equals("support"))); return;
                }
                if (args.length >= 3 && args.length % 2 == 1 && args[0].equals("options")) {
                    String[] pairs = new String[args.length - 1];
                    System.arraycopy(args, 1, pairs, 0, pairs.length);
                    printStatus(transaction().updateHeaterOptions(pairs), false);
                    return;
                }
                if (args.length == 1 && args[0].equals("home")) {
                    VehicleLocation fix = VehicleLocationSource.current();
                    c.set("homeLat", Double.toString(fix.latitude)); c.set("homeLon", Double.toString(fix.longitude));
                    transaction().commit(c); printStatus(c, false); return;
                }
                if (args.length == 1 && args[0].equals("restore")) {
                    for (String id : Config.IDS) c.set(id, "false");
                } else if (args.length >= 3 && args.length % 2 == 1 && (args[0].equals("set") || args[0].equals("update"))) {
                    for (int i = 1; i < args.length; i += 2) c.set(args[i], args[i + 1]);
                    // With the right-hand pop-over disabled the nav handle opens nothing, so it is always hidden.
                    if (c.enabled("popover")) c.set("nav", "true");
                    c.validateHome();
                    if (c.enabled("projection") && c.changedFrom(previous, "projection")) ProjectionController.requireSupported();
                    if (c.enabled("chargeaway") && c.changedFrom(previous, "chargeaway")) ScheduledCharging.requireSupported();
                    if (c.needsVehicleCheck(previous)) Platform.requireVehicle();
                    if (c.needsDualZoneCheck(previous)) Platform.requireDualZone();
                    if (c.enabled("dst") && c.dstChanged(previous)) DstRules.baseOffsetMinutes(Platform.property("persist.sys.timezone"));
                } else throw new IllegalArgumentException("Usage: settings | summary | status | report | support | home | restore | reconcile | self-update {ID FILE CHECK INNER}… | update-source add ID OWNER/REPO | update-source remove ID | uninstall ID | set/update/options KEY VALUE [KEY VALUE…]");
                // Intent survives termination between a /system write and config commit.
                if (restoring) transaction().commitFull(c); else transaction().commit(c);
                if (args[0].equals("update")) { printStatus(c, false); return; }
                System.out.println("Saved. Static file changes take effect after a head-unit restart.");
            }
        } catch (Exception e) { System.err.println("Tweaks: " + e.getMessage()); System.exit(1); }
    }
    /**
     * Stages the releases the app downloaded, each as ID FILE CHECK INNER: CHECK is Tilepad's signature
     * file or GitHub's SHA-256 of FILE, INNER the package's path in a USB bundle or "-". Checks them all
     * (SelfUpdate.stage), then starts their install, which runs on after this command and the app have
     * gone and restarts the head unit. Returns the ids in install order.
     */
    private static String selfUpdate(String[] args) throws Exception {
        File dir = SelfUpdate.DIR; String boot = Platform.boot(); long now = System.currentTimeMillis();
        if (SelfUpdate.installing(dir, boot, now)) throw new IOException("An update is already installing. The head unit restarts when it's done.");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        Platform.exec("chmod", "0700", dir.getPath());
        SelfUpdate.tidy(dir);
        Properties sources = Files.properties(SelfUpdate.SOURCES);
        List<SelfUpdate.Item> items = new ArrayList<>();
        try {
            for (int i = 1; i < args.length; i += 4) {
                String id = args[i], check = args[i + 2], inner = args[i + 3].equals("-") ? null : args[i + 3];
                if (!SelfUpdate.validId(id)) throw new IOException("That isn't a NEMESIS package id: " + id);
                for (SelfUpdate.Item other : items) if (other.id.equals(id)) throw new IOException(id + " is listed twice.");
                boolean tilepad = id.equals(SelfUpdate.TILEPAD);
                if (!tilepad) {
                    // Only what the user linked, and not NEMESIS's own. One not installed yet installs fresh.
                    if (sources.getProperty(id) == null) throw new IOException(id + " isn't linked to a GitHub repository.");
                    SelfUpdate.Package p = SelfUpdate.installedPackage(SelfUpdate.PACKAGES, id);
                    if (p != null && p.locked) throw new IOException(id + " can't be updated from here.");
                    if (!check.matches("[0-9a-fA-F]{64}")) throw new IOException("GitHub gave no checksum for " + id + ".");
                } else if (inner != null) throw new IOException("Tilepad updates from tilepad.zip.");
                String installed = SelfUpdate.installedVersion(SelfUpdate.APPS, id);
                if (installed == null && (tilepad || SelfUpdate.installedPackage(SelfUpdate.PACKAGES, id) != null))
                    throw new IOException("Can't read the installed " + (tilepad ? "Tilepad" : id) + " version.");
                SelfUpdate.Item item = new SelfUpdate.Item(id, new File(args[i + 1]), check, inner);
                SelfUpdate.stage(item, dir, installed, tilepad ? SelfUpdate.releaseCertificate().getPublicKey() : null);
                items.add(item);
            }
        } catch (Exception e) { SelfUpdate.tidy(dir); throw e; }
        items = SelfUpdate.order(items);
        File script = new File(dir, SelfUpdate.SCRIPT);
        Files.write(script, SelfUpdate.script(dir, items).getBytes("UTF-8"));
        SelfUpdate.started(dir, items, boot, now);
        // Its own process, reparented to init when this one exits; output goes to its logs, not to the app.
        Platform.exec("sh", "-c", "trap '' HUP; sh '" + script.getPath() + "' </dev/null >/dev/null 2>&1 &");
        StringBuilder ids = new StringBuilder();
        for (SelfUpdate.Item i : items) ids.append(ids.length() == 0 ? "" : ",").append(i.id);
        return ids.toString();
    }
    public static Config load() throws IOException { return transaction().load(); }
    public static void recover() throws Exception { transaction().recover(); }
    private static Transaction transaction() {
        return new Transaction(Platform.STATE, new Transaction.Apply() {
            @Override public void run(Config next, Config previous, boolean rollback) throws Exception {
                otaClient().apply(next, previous);
                Platform.patches().apply(next, previous, Platform.boot());
                if (next.changedFrom(previous, "dst")) {
                    if (!next.enabled("dst")) DstController.restore();
                    else if (rollback) DstController.update(next, System.currentTimeMillis());
                }
            }
        });
    }
    private static OtaClient otaClient() {
        return new OtaClient(Platform.STATE, new OtaClient.Commands() {
            @Override public String run(String... args) throws Exception { return Platform.exec(args); }
        });
    }
    private static void printStatus(Config c, boolean verify) throws Exception {
        status(c, verify).store(System.out, "Tilepad Tweaks status");
    }
    private static Properties status(Config c, boolean verify) throws Exception {
        Properties out = (Properties) c.values.clone();
        for (String key : new String[]{"region", "threshold", "driver", "wheel", "passenger", "driverLevel", "passengerLevel", "units", "homeLat", "homeLon"}) out.setProperty(key, c.get(key));
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
                    if (id.equals("sync") || id.equals("driveronly")) Platform.requireDualZone();
                    if (id.equals("dst")) DstRules.baseOffsetMinutes(Platform.property("persist.sys.timezone"));
                    if (id.equals("projection")) ProjectionController.requireSupported();
                    else if (id.equals("chargeaway")) ScheduledCharging.requireSupported();
                    else if (id.equals("ota")) otaClient().check(c);
                    else if (!id.equals("dst") && !id.equals("heaters") && !id.equals("sync") && !id.equals("driveronly")) Platform.patches().check(id, c);
                }
            } catch (Exception e) { reason = e.getMessage(); }
            out.setProperty("unavailable." + id, reason);
            File status = new File(Platform.STATE, "status-" + id);
            if (status.isFile()) out.setProperty("status." + id, new String(Files.read(status), "UTF-8"));
        }
        // fitted.<heater>: whether the car reports that heater; absent when it can't be read.
        if (verify && vehicleError == null) for (String key : new String[]{"driver", "passenger", "wheel"}) {
            Boolean fitted = TweaksRuntime.fitted(key);
            if (fitted != null) out.setProperty("fitted." + key, fitted.toString());
        }
        // report: this firmware's compatibility report already on the USB drive, if anything is unavailable.
        boolean incompatible = false;
        for (String id : Config.IDS) incompatible |= !out.getProperty("unavailable." + id).isEmpty();
        if (verify && incompatible) out.setProperty("report", UsbReport.existing());
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
        long now = System.currentTimeMillis();
        out.putAll(SelfUpdate.result(SelfUpdate.DIR, SelfUpdate.APPS, Platform.boot(), now));
        if (!SelfUpdate.installing(SelfUpdate.DIR, Platform.boot(), now)) SelfUpdate.tidy(SelfUpdate.DIR);
        // source.<id>: the GitHub repository Check for Updates follows for that package.
        Properties sources = Files.properties(SelfUpdate.SOURCES);
        for (String id : sources.stringPropertyNames()) out.setProperty("source." + id, sources.getProperty(id));
        return out;
    }
    private TweaksCommand() { }
}
