package com.tilepad.tweakcore;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

/** Gathers a {@link CompatReport} on the head unit and saves it to the USB drive. */
final class UsbReport {
    /** Collects the report and writes it to the USB drive; returns the file name. */
    static String save(Properties status) throws Exception {
        String mount = CompatReport.usbMount(new String(Files.read(new File("/proc/mounts")), "UTF-8"));
        if (mount == null) throw new IOException("No USB drive found. Insert one and try again.");
        Map<String, String> identity = new LinkedHashMap<>();
        identity.put("generated", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date()));
        for (String name : CompatReport.PROPERTIES) identity.put(name, Platform.property(name));
        Map<String, String> files = new LinkedHashMap<>();
        Set<String> paths = new LinkedHashSet<>(Arrays.asList(CompatReport.GATED));
        File[] recipes = new File(Platform.HOME, "tweaks/patches").listFiles();
        if (recipes != null) {
            Arrays.sort(recipes);
            for (File recipe : recipes) {
                Properties p = Files.properties(recipe);
                for (String key : new String[]{"path", "apkPath"}) if (p.getProperty(key) != null) paths.add(p.getProperty(key));
            }
        }
        // Track-button swap: each model's key layout (only the captured ones have recipes).
        File[] layouts = new File("/system/usr/keylayout").listFiles();
        if (layouts != null) {
            Arrays.sort(layouts);
            for (File f : layouts) if (f.getName().startsWith("daudio_") && f.getName().endsWith("_base.kl")) paths.add(f.getPath());
        }
        for (String path : paths) {
            File f = new File(path);
            files.put(path, f.isFile() ? Files.hash(f) : "missing");
            File stock = new File(Platform.STATE, "files/" + f.getName() + "/stock");
            if (stock.isFile()) files.put(path + " (stock)", Files.hash(stock));
        }
        String name = CompatReport.fileName(identity.get("ro.build.product"));
        String body = CompatReport.text(identity, files, status);
        // The drive is media_rw's (uid 1023, umask 0007), and this root channel has no DAC
        // override, so write as that uid.
        Platform.exec("/system/xbin/nemesis", "root", "1023", "-c",
            "printf '%s' " + quote(body) + " > " + quote(mount + "/" + name) + " && sync");
        return name;
    }

    private static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
    private UsbReport() { }
}
