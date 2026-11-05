package com.tilepad.tweakcore;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

/** OEM switch synchronization. Snapshot before first mutation; don't undo a later manual choice. */
public final class DstController {
    private static final String NAME = "com.hkmc.micom.time", TOKEN = "com.hkmc.time.ITimePolicyService";
    private static File snapshot() { return new File(Platform.STATE, "dst-original.properties"); }
    public static long update(Config c, long now) throws Exception {
        Platform.requireVehicle();
        if (now < 1577836800000L) throw new IOException("Waiting for an accurate clock (date is before 2020).");
        int offset = DstRules.baseOffsetMinutes(Platform.property("persist.sys.timezone"));
        boolean wanted = DstRules.active(now, offset, c.get("region"));
        int have = Platform.integer(NAME, TOKEN, 3);
        if (have != 0 && have != 1) throw new IOException("Invalid DST response");
        String summer = Platform.exec("settings", "get", "system", "summer_time");
        Properties p = Files.properties(snapshot());
        if (!p.containsKey("dst")) { p.setProperty("dst", Integer.toString(have)); p.setProperty("summer", summer); }
        p.setProperty("ours", wanted ? "1" : "0");
        Files.store(snapshot(), p); // write ahead of both setting changes
        int expected = wanted ? 1 : 0;
        if (have != expected) Platform.command(NAME, TOKEN, 4, expected);
        if (Platform.integer(NAME, TOKEN, 3) != expected) throw new IOException("Head unit did not accept the DST setting");
        if (!summer.equals(Integer.toString(expected))) Platform.exec("settings", "put", "system", "summer_time", Integer.toString(expected));
        Platform.status("dst", wanted ? "Daylight saving time is active." : "Standard time is active.");
        return DstRules.next(now, offset, c.get("region"));
    }
    public static void restore() throws Exception {
        File file = snapshot(); if (!file.isFile()) return;
        Properties p = Files.properties(file);
        int have = Platform.integer(NAME, TOKEN, 3);
        if (Integer.toString(have).equals(p.getProperty("ours"))) {
            Platform.command(NAME, TOKEN, 4, Integer.parseInt(p.getProperty("dst")));
            if (Platform.integer(NAME, TOKEN, 3) != Integer.parseInt(p.getProperty("dst"))) throw new IOException("Could not restore original DST setting");
        }
        String summer = Platform.exec("settings", "get", "system", "summer_time");
        if (summer.equals(p.getProperty("ours"))) {
            if (p.getProperty("summer").equals("null")) Platform.exec("settings", "delete", "system", "summer_time");
            else Platform.exec("settings", "put", "system", "summer_time", p.getProperty("summer"));
        }
        if (!file.delete()) throw new IOException("Cannot retire DST snapshot");
        Platform.status("dst", "Automatic changes are off. Previous settings restored where still owned by Tilepad.");
    }
    private DstController() { }
}
