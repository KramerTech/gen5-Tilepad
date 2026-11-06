package com.tilepad.tweakcore;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Gathers a {@link CompatReport} on the head unit and streams it to the USB drive as one zip,
 * with the firmware code every gate covers, so a single log is enough to add support.
 */
final class UsbReport {
    private static final String NEMESIS = "/system/xbin/nemesis";

    /** The file name if this firmware's log is already on the USB drive, otherwise "". */
    static String existing() {
        try {
            String mount = usbMount();
            if (mount == null) return "";
            String name = CompatReport.fileName(Platform.property("ro.build.product"));
            // Only media_rw can look inside the drive; a missing file fails the test.
            Platform.exec(NEMESIS, "root", "1023", "-c", "[ -f " + CompatReport.quote(mount + "/" + name) + " ]");
            return name;
        } catch (Exception e) { return ""; }
    }

    /** Collects the log and writes it to the USB drive; returns the file name. */
    static String save(Properties status) throws Exception {
        String mount = usbMount();
        if (mount == null) throw new IOException("No USB drive found. Insert one and try again.");
        Map<String, String> identity = new LinkedHashMap<>();
        identity.put("generated", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date()));
        for (String name : CompatReport.PROPERTIES) identity.put(name, Platform.property(name));

        // Hashed: every gated file, each patch recipe's target and paired APK, the key layouts the car loaded.
        Set<String> paths = new LinkedHashSet<>(Arrays.asList(CompatReport.GATED));
        File[] recipes = new File(Platform.HOME, "tweaks/patches").listFiles();
        if (recipes != null) {
            Arrays.sort(recipes);
            for (File recipe : recipes) {
                Properties p = Files.properties(recipe);
                for (String key : new String[]{"path", "apkPath"}) if (p.getProperty(key) != null) paths.add(p.getProperty(key));
            }
        }
        List<String> layouts = Collections.emptyList();
        try { layouts = PatchManager.loadedLayouts(Platform.exec("dumpsys", "input")); } catch (Exception ignored) { }
        paths.addAll(layouts);
        Map<String, String> hashes = new LinkedHashMap<>();
        Map<String, File> stock = new LinkedHashMap<>();
        for (String path : paths) {
            File f = new File(path);
            hashes.put(path, f.isFile() ? Files.hash(f) : "missing");
            File backup = new File(Platform.STATE, "files/" + f.getName() + "/stock");
            if (backup.isFile()) { hashes.put(path + " (stock)", Files.hash(backup)); stock.put(f.getName(), backup); }
        }

        // Copied: the code behind those gates, which an APK only pairs with. All framework ODEX
        // (the full boot class path, needed to decompile any of it), the gated apps' ODEX, the
        // loaded key layouts and build.prop. APKs hold resources and are only hashed.
        Set<File> copied = new TreeSet<>();
        File[] framework = new File("/system/framework").listFiles();
        if (framework != null) for (File f : framework) if (f.getName().endsWith(".odex")) copied.add(f);
        for (String path : paths) {
            File f = new File(path.endsWith(".apk") ? path.substring(0, path.length() - 4) + ".odex" : path);
            if (f.isFile() && !path.startsWith("/system/framework/")) copied.add(f);
        }
        copied.add(new File("/system/build.prop"));

        String name = CompatReport.fileName(identity.get("ro.build.product"));
        String target = CompatReport.quote(mount + "/" + name), partial = CompatReport.quote(mount + "/" + name + ".partial");
        // The drive is media_rw's (uid 1023, umask 0007) and this root channel has no DAC override,
        // so a shell as that uid writes what this process streams to it.
        Process writer = new ProcessBuilder(NEMESIS, "root", "1023", "-c",
            "cat > " + partial + " && mv " + partial + " " + target + " && sync").redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread drain = drain(writer.getInputStream(), output);
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(writer.getOutputStream(), 1 << 16))) {
            entry(zip, "tilepad-compat.txt", CompatReport.text(identity, hashes, status).getBytes("UTF-8"));
            for (File f : copied) copy(zip, f.getPath().substring(1), f);
            for (Map.Entry<String, File> e : stock.entrySet()) copy(zip, "tilepad-stock/" + e.getKey(), e.getValue());
            for (String service : CompatReport.SERVICES) {
                String dump;
                try { dump = CompatReport.numericLines(Platform.exec("dumpsys", service)); } catch (Exception e) { dump = "unavailable: " + e.getMessage() + "\n"; }
                entry(zip, "dumps/" + service + ".txt", dump.getBytes("UTF-8"));
            }
        } catch (IOException e) {
            writer.destroy();
            cleanUp(partial);
            throw new IOException("Couldn't save the log (" + e.getMessage() + "). Keep the USB drive plugged in and try again.");
        }
        int exit = writer.waitFor();
        drain.join();
        if (exit != 0) {
            cleanUp(partial);
            throw new IOException("Couldn't write to the USB drive: " + output.toString("UTF-8").trim());
        }
        return name;
    }

    private static String usbMount() throws IOException {
        return CompatReport.usbMount(new String(Files.read(new File("/proc/mounts")), "UTF-8"));
    }
    private static void entry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(data); zip.closeEntry();
    }
    private static void copy(ZipOutputStream zip, String name, File file) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream in = new FileInputStream(file)) {
            byte[] b = new byte[1 << 16]; for (int n; (n = in.read(b)) != -1;) zip.write(b, 0, n);
        }
        zip.closeEntry();
    }
    private static Thread drain(final InputStream in, final ByteArrayOutputStream out) {
        Thread t = new Thread() { @Override public void run() {
            try { byte[] b = new byte[4096]; for (int n; (n = in.read(b)) != -1;) if (out.size() < 8192) out.write(b, 0, n); }
            catch (IOException ignored) { }
        } };
        t.start(); return t;
    }
    private static void cleanUp(String partial) {
        try { Platform.exec(NEMESIS, "root", "1023", "-c", "rm -f " + partial); } catch (Exception ignored) { }
    }
    private UsbReport() { }
}
