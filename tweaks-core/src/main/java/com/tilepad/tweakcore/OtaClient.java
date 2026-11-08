package com.tilepad.tweakcore;

import java.io.File;
import java.io.IOException;

/** Android's persistent package switch; no APK changes or background enforcement. */
public final class OtaClient {
    public static final String PACKAGE = "com.hkmc.system.app.OTAClient";
    public interface Commands { String run(String... args) throws Exception; }
    private final File record;
    private final Commands commands;
    public OtaClient(File state, Commands commands) {
        this.record = new File(state, "ota-client.disabled"); this.commands = commands;
    }
    public void check(Config config) throws Exception {
        requireInstalled();
        if (config.enabled("ota") && !listed("-d"))
            throw new IOException("The OTA client is enabled. Turn this tweak off and on again to disable it.");
    }
    public void apply(Config next, Config previous) throws Exception {
        if (previous != null && !next.changedFrom(previous, "ota")) return;
        if (next.enabled("ota")) {
            requireInstalled();
            // Write before touching Android so restore also works after interruption.
            Files.write(record, PACKAGE.getBytes("UTF-8"));
            set(false);
        } else if (record.isFile()) {
            // Explicitly reenable, including when it was already disabled at opt-in.
            set(true);
            if (record.exists() && !record.delete()) throw new IOException("Cannot retire the OTA client recovery record");
        }
    }
    private void requireInstalled() throws Exception {
        for (String line : commands.run("pm", "path", PACKAGE).split("\n"))
            if (line.trim().startsWith("package:/")) return;
        throw new IOException("This head unit's OTA client wasn't found.");
    }
    private boolean listed(String filter) throws Exception {
        for (String line : commands.run("pm", "list", "packages", filter, "--user", "0", PACKAGE).split("\n"))
            if (line.trim().equals("package:" + PACKAGE)) return true;
        return false;
    }
    private void set(boolean enabled) throws Exception {
        commands.run("pm", enabled ? "enable" : "disable", "--user", "0", PACKAGE);
        // pm may report an error without a nonzero exit status on older Android.
        if (!listed(enabled ? "-e" : "-d"))
            throw new IOException("The OTA client could not be " + (enabled ? "enabled" : "disabled") + ".");
    }
}
