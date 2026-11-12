package com.tilepad.tweakcore;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Gathers a {@link CompatReport} on the head unit and streams it to the USB drive as one zip,
 * with the firmware code every gate covers, so a single log is enough to add support. A support
 * log (Tweaks > Support) adds what the head unit is doing: see {@link #support}.
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
    static String save(Properties status, Config config, boolean support) throws Exception {
        String mount = usbMount();
        if (mount == null) throw new IOException("No USB drive found. Insert one and try again.");
        // First, while it's still on screen: what the head unit is doing as the owner asks for the log.
        Map<String, byte[]> moment = support ? support(status, config) : Collections.<String, byte[]>emptyMap();
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

        String name = support ? CompatReport.supportFileName(identity.get("ro.build.product"), new Date())
            : CompatReport.fileName(identity.get("ro.build.product"));
        String target = CompatReport.quote(mount + "/" + name), partial = CompatReport.quote(mount + "/" + name + ".partial");
        // The drive is media_rw's (uid 1023, umask 0007) and this root channel has no DAC override,
        // so a shell as that uid writes what this process streams to it.
        Process writer = new ProcessBuilder(NEMESIS, "root", "1023", "-c",
            "cat > " + partial + " && mv " + partial + " " + target + " && sync").redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread drain = drain(writer.getInputStream(), output);
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(writer.getOutputStream(), 1 << 16))) {
            entry(zip, "tilepad-compat.txt", CompatReport.text(identity, hashes, status).getBytes("UTF-8"));
            for (Map.Entry<String, byte[]> e : moment.entrySet()) entry(zip, "support/" + e.getKey(), e.getValue());
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

    /**
     * What a support log adds, each as a text file: the windows and activities on screen, the
     * kept logcat lines ({@link CompatReport#supportLog}), the panel processes (start time and
     * the code files they mapped, "(deleted)" when a file was replaced after they started), every
     * patched file's state and the saved settings, and the tail of Tilepad's service log. No GPS,
     * Home, media, call or contact text.
     */
    private static Map<String, byte[]> support(Properties status, Config config) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        out.put("windows.txt", utf8(capture(new Capture() { @Override public String run() throws Exception {
            return CompatReport.windowLines(tail(8 << 20, "dumpsys", "window", "windows"))
                + "\n" + CompatReport.activityLines(tail(8 << 20, "dumpsys", "activity", "activities")); } })));
        Map<String, String> panels = panelProcesses();
        String logcat;
        // The log group may read every app's lines; this root channel has no CAP_SYSLOG.
        try { logcat = tail(2 << 20, NEMESIS, "root", "1007", "-c", "logcat -d -v threadtime"); }
        catch (Exception e) { logcat = ""; out.put("logcat-error.txt", utf8(e.getMessage())); }
        Set<String> pids = new HashSet<>(panels.keySet());
        pids.addAll(CompatReport.startedPids(logcat, CompatReport.PANELS));
        String kept = CompatReport.supportLog(logcat, pids, 4000);
        out.put("logcat.txt", utf8("# " + logcat.split("\n").length + " lines read, " + (kept.isEmpty() ? 0 : kept.split("\n").length)
            + " kept; panel pids " + new TreeSet<>(pids) + "\n" + kept));
        StringBuilder processes = new StringBuilder();
        for (Map.Entry<String, String> e : panels.entrySet()) processes.append(e.getValue());
        out.put("processes.txt", utf8(processes.length() == 0 ? "No panel process found.\n" : processes.toString()));
        StringBuilder state = new StringBuilder();
        state.append("[boot]\nboot_id=").append(capture(new Capture() { @Override public String run() throws Exception { return Platform.boot(); } }))
            .append("\nuptime=").append(capture(new Capture() { @Override public String run() throws Exception { return new String(Files.read(new File("/proc/uptime")), "UTF-8").trim(); } }))
            .append("\n\n[runtime.properties] what the tweaks service recorded as this boot began\n")
            .append(capture(new Capture() { @Override public String run() throws Exception { return new String(Files.read(new File(Platform.STATE, "runtime.properties")), "UTF-8"); } }))
            .append("\n[settings] Home excluded\n");
        for (String key : new TreeSet<>(config.values.stringPropertyNames()))
            if (!key.startsWith("home")) state.append(key).append('=').append(config.values.getProperty(key)).append('\n');
        state.append("\n[status] each tweak's latest runtime report\n");
        for (String key : new TreeSet<>(status.stringPropertyNames()))
            if (key.startsWith("status.") || key.startsWith("pending.")) state.append(key).append('=').append(status.getProperty(key).replace('\n', ' ')).append('\n');
        state.append("\n[files]\n").append(Platform.patches().audit(config));
        out.put("state.txt", utf8(state.toString()));
        out.put("tilepad-service.log", utf8(capture(new Capture() { @Override public String run() throws Exception {
            File log = new File("/data/nemesis/logs/tilepad/longstar.log");
            return tailFile(log, 256 << 10).replaceAll("\\d+(\\.\\d+)? miles from Home", "… miles from Home"); } })));
        // NEMESIS's own record of Tilepad's installs, updates, hooks and service restarts.
        out.put("nemesis.log", utf8(capture(new Capture() { @Override public String run() throws Exception {
            StringBuilder lines = new StringBuilder();
            for (String line : tailFile(new File("/data/nemesis/nemesis.log"), 512 << 10).split("\n"))
                if (line.contains("tilepad")) lines.append(line).append('\n');
            return lines.toString(); } })));
        // The last update started from Support, if any: its record and NEMESIS's output.
        if (new File(SelfUpdate.DIR, SelfUpdate.RESULT).isFile())
            out.put("self-update.txt", utf8(capture(new Capture() { @Override public String run() throws Exception {
                File log = new File(SelfUpdate.DIR, SelfUpdate.LOG);
                return new String(Files.read(new File(SelfUpdate.DIR, SelfUpdate.RESULT)), "UTF-8")
                    + "\n[" + SelfUpdate.LOG + "]\n" + (log.isFile() ? tailFile(log, 64 << 10) : "missing\n"); } })));
        return out;
    }
    /** Pid to a description of each running panel process: uid, start time and mapped code files. */
    private static Map<String, String> panelProcesses() {
        Map<String, String> out = new TreeMap<>();
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return out;
        double uptime;
        try { uptime = Double.parseDouble(new String(Files.read(new File("/proc/uptime")), "UTF-8").trim().split(" ")[0]); }
        catch (Exception e) { uptime = -1; }
        for (File proc : procs) {
            String pid = proc.getName();
            if (!pid.matches("\\d+")) continue;
            String name;
            try { name = new String(Files.read(new File(proc, "cmdline")), "UTF-8").split("\u0000")[0]; } catch (Exception e) { continue; }
            if (!Arrays.asList(CompatReport.PANELS).contains(name)) continue;
            StringBuilder d = new StringBuilder(name).append(" pid=").append(pid);
            String uid = "";
            try {
                for (String line : new String(Files.read(new File(proc, "status")), "UTF-8").split("\n"))
                    if (line.startsWith("Uid:")) uid = line.substring(4).trim().split("\\s+")[0];
                String stat = new String(Files.read(new File(proc, "stat")), "UTF-8");
                long started = Long.parseLong(stat.substring(stat.lastIndexOf(')') + 2).split(" ")[19]);
                d.append(" uid=").append(uid);
                if (uptime >= 0) d.append(" started=").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
                    .format(new Date(System.currentTimeMillis() - (long) ((uptime - started / 100.0) * 1000))));
            } catch (Exception e) { d.append(" (").append(e.getMessage()).append(')'); }
            d.append('\n');
            // Only the process's own uid may read its maps.
            if (!uid.matches("\\d+")) d.append("  maps unavailable: no uid\n");
            else try {
                Set<String> code = new TreeSet<>();
                for (String line : Platform.exec(NEMESIS, "root", uid, "-c", "cat /proc/" + pid + "/maps").split("\n")) {
                    int at = line.indexOf('/');
                    if (at < 0) continue;
                    String file = line.substring(at);
                    if (file.matches(".*\\.(odex|dex|apk|jar)( \\(deleted\\))?")) code.add(file);
                }
                for (String file : code) d.append("  maps ").append(file).append('\n');
            } catch (Exception e) { d.append("  maps unavailable: ").append(e.getMessage()).append('\n'); }
            out.put(pid, d.toString());
        }
        return out;
    }
    private interface Capture { String run() throws Exception; }
    private static String capture(Capture c) {
        try { return c.run(); } catch (Exception e) { return "unavailable: " + e.getMessage() + "\n"; }
    }
    private static byte[] utf8(String s) {
        try { return s.getBytes("UTF-8"); } catch (UnsupportedEncodingException e) { throw new AssertionError(e); }
    }
    /** The last {@code limit} bytes a command prints (logcat's newest lines). */
    private static String tail(int limit, String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] ring = new byte[limit]; long total = 0;
        try (InputStream in = p.getInputStream()) {
            byte[] b = new byte[1 << 16];
            for (int n; (n = in.read(b)) != -1; total += n) for (int i = 0; i < n; i++) ring[(int) ((total + i) % limit)] = b[i];
        }
        if (p.waitFor() != 0) throw new IOException("Command failed: " + command[command.length - 1]);
        if (total <= limit) return new String(ring, 0, (int) total, "UTF-8");
        int start = (int) (total % limit);
        byte[] ordered = new byte[limit];
        System.arraycopy(ring, start, ordered, 0, limit - start); System.arraycopy(ring, 0, ordered, limit - start, start);
        return new String(ordered, "UTF-8");
    }
    private static String tailFile(File file, int limit) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            long from = Math.max(0, f.length() - limit);
            byte[] b = new byte[(int) (f.length() - from)];
            f.seek(from); f.readFully(b);
            return new String(b, "UTF-8");
        }
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
