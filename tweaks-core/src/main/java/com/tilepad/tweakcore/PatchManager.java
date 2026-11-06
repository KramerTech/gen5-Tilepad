package com.tilepad.tweakcore;

import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.Adler32;

/** Own-stock backups and a per-file write-ahead journal, shared by both right-panel switches. */
public final class PatchManager {
    public interface SystemOps {
        File file(String absolutePath);
        void writable() throws Exception;
        void finish() throws Exception;
        void replace(File target, byte[] data) throws Exception;
        /** The steering-wheel key layouts the car loaded this boot (absolute /system paths). */
        List<String> layouts() throws Exception;
    }
    private final File state, recipes;
    private final SystemOps system;
    public PatchManager(File state, File recipes, SystemOps system) {
        this.state = state; this.recipes = recipes; this.system = system;
    }
    private List<Properties> profiles() throws IOException {
        File[] files = recipes.listFiles();
        if (files == null) throw new IOException("Tilepad's tweak patch recipes are missing. Reinstall the complete Tilepad bundle.");
        Arrays.sort(files);
        // One recipe per firmware release for each patched file; the car uses the one for its stock.
        Map<String, List<Properties>> byPath = new LinkedHashMap<>();
        Properties tracks = null;
        for (File f : files) {
            if (!f.getName().endsWith(".properties")) continue;
            Properties p = Files.properties(f);
            if ("tracks".equals(p.getProperty("kind"))) { tracks = p; continue; }
            List<Properties> same = byPath.get(p.getProperty("path"));
            if (same == null) byPath.put(p.getProperty("path"), same = new ArrayList<>());
            same.add(p);
        }
        if (byPath.size() != 2 || tracks == null) throw new IOException("Incomplete patch recipes");
        List<Properties> result = new ArrayList<>();
        for (Map.Entry<String, List<Properties>> e : byPath.entrySet()) result.add(matching(e.getKey(), e.getValue()));
        result.addAll(trackRecipes(tracks));
        return result;
    }
    /** The recipe made for this file's stock (Tilepad's backup, or the untouched file). */
    private Properties matching(String path, List<Properties> candidates) throws IOException {
        if (candidates.size() == 1) return candidates.get(0);
        File backup = new File(state, "files/" + new File(path).getName() + "/stock");
        File source = backup.isFile() ? backup : system.file(path);
        String stock = source.isFile() ? Files.hash(Files.read(source)) : "";
        for (Properties p : candidates) if (stock.equals(p.getProperty("stockSha256"))) return p;
        return candidates.get(0);  // none fits: its stock check reports the file as unsupported
    }
    /**
     * The track swap edits two text lines, identical in every model's layout, so it isn't tied to
     * one file's hash: it targets the layouts the car loaded plus any it already backed up (to
     * restore them), each with its own stock (the backup, or the untouched file).
     */
    private List<Properties> trackRecipes(Properties template) throws IOException {
        Set<String> paths = new TreeSet<>();
        File[] backed = new File(state, "files").listFiles();
        if (backed != null) for (File dir : backed) {
            String path = Files.properties(new File(dir, "journal.properties")).getProperty("path", "");
            if (LAYOUT.matcher(path).matches() && new File(dir, "stock").isFile()) paths.add(path);
        }
        List<String> loaded;
        try { loaded = system.layouts(); } catch (Exception e) { loaded = Collections.emptyList(); }
        for (String path : loaded) if (LAYOUT.matcher(path).matches()) paths.add(path);
        List<Properties> result = new ArrayList<>();
        for (String path : paths) {
            File backup = new File(state, "files/" + new File(path).getName() + "/stock");
            File source = backup.isFile() ? backup : system.file(path);
            if (!source.isFile()) continue;
            Properties p = (Properties) template.clone();
            p.setProperty("path", path);
            p.setProperty("stockSha256", Files.hash(Files.read(source)));
            result.add(p);
        }
        return result;
    }
    private static final String UNSUPPORTED = "This firmware's file isn't supported yet, or another mod changed it (if so, disable that mod first). "
        + "Capture and submit a log to help add compatibility.";
    static final Pattern LAYOUT = Pattern.compile("/system/usr/keylayout/daudio_[A-Za-z0-9_]+_base\\.kl");
    /** KeyLayoutFile of each daudio_* input device in {@code dumpsys input}. */
    public static List<String> loadedLayouts(String dumpsysInput) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^\\s+\\d+: (daudio_\\S*)\\s*\\n(?:.*\\n)*?\\s+KeyLayoutFile: (\\S*)").matcher(dumpsysInput);
        while (m.find()) if (LAYOUT.matcher(m.group(2)).matches() && !out.contains(m.group(2))) out.add(m.group(2));
        return out;
    }
    public void apply(Config config, String boot) throws Exception { apply(config, null, boot); }
    public void apply(Config config, Config previous, String boot) throws Exception {
        if (previous != null && !config.staticChanged(previous)) return;
        // Validate and stage every affected target BEFORE remounting or writing any.
        List<Change> changes = new ArrayList<>();
        for (Properties p : profiles()) {
            if (previous != null && !affected(p, config, previous)) continue;
            String path = p.getProperty("path");
            File journalDir = new File(state, "files/" + new File(path).getName());
            File backup = new File(journalDir, "stock");
            File journal = new File(journalDir, "journal.properties");
            boolean wanted = relevant(p, config);
            if (!wanted && !backup.isFile()) continue;
            File target = system.file(path);
            byte[] current = Files.read(target);
            byte[] stock = backup.isFile() ? Files.read(backup) : current;
            require(Files.hash(stock).equals(p.getProperty("stockSha256")), UNSUPPORTED);
            Properties j = Files.properties(journal);
            String have = Files.hash(current);
            require(have.equals(p.getProperty("stockSha256")) || have.equals(j.getProperty("current")) || have.equals(j.getProperty("next")),
                "Another package changed " + path + ". Its file will not be overwritten; Tilepad's stock backup is retained.");
            byte[] desired = transform(p, stock, config);
            // A firmware update may have replaced the APK: restore is allowed only
            // while the owning APK is still the exact one this optimized code belongs to.
            if (Arrays.equals(current, desired)) continue;
            if (p.getProperty("apkPath") != null) require(Files.hash(system.file(p.getProperty("apkPath"))).equals(p.getProperty("apkSha256")),
                "The APK paired with " + path + " has changed; refusing stale optimized code.");
            if (!backup.isFile()) Files.write(backup, stock);
            j.setProperty("path", path);
            j.setProperty("current", have);
            j.setProperty("next", Files.hash(desired));
            Files.store(journal, j);
            changes.add(new Change(target, desired, journal, j));
        }
        if (changes.isEmpty()) return;
        system.writable();
        try {
            for (Change c : changes) {
                system.replace(c.target, c.desired);
                require(Arrays.equals(Files.read(c.target), c.desired), "Write did not verify: " + c.target);
                c.values.setProperty("current", c.values.getProperty("next"));
                c.values.remove("next");
                Files.store(c.journal, c.values);
                Files.write(new File(state, "restart.boot"), boot.getBytes("UTF-8"));
            }
        } finally { system.finish(); }
    }
    public void check(String id, Config config) throws Exception {
        Config trial = new Config((Properties) config.values.clone()); trial.set(id, "true");
        List<Properties> profiles = profiles();
        if (id.equals("tracks")) {
            boolean any = false;
            for (Properties p : profiles) any |= "tracks".equals(p.getProperty("kind"));
            require(any, "The car's steering-wheel key layout wasn't found. Capture and submit a log to help add compatibility.");
        }
        for (Properties p : profiles) {
            if (!(p.containsKey(id) || (id.equals("tracks") && "tracks".equals(p.getProperty("kind"))))) continue;
            File stockFile = new File(state, "files/" + new File(p.getProperty("path")).getName() + "/stock");
            byte[] stock = Files.read(stockFile.isFile() ? stockFile : system.file(p.getProperty("path")));
            require(Files.hash(stock).equals(p.getProperty("stockSha256")), UNSUPPORTED);
            Properties journal = Files.properties(new File(stockFile.getParentFile(), "journal.properties"));
            String current = Files.hash(Files.read(system.file(p.getProperty("path"))));
            require(current.equals(p.getProperty("stockSha256")) || current.equals(journal.getProperty("current")) || current.equals(journal.getProperty("next")),
                "Another mod changed this file. Tilepad will not overwrite it.");
            if (p.containsKey("apkPath")) require(Files.hash(system.file(p.getProperty("apkPath"))).equals(p.getProperty("apkSha256")), "Unsupported paired APK");
            transform(p, stock, trial);
        }
    }
    private static boolean affected(Properties p, Config next, Config previous) {
        if ("tracks".equals(p.getProperty("kind"))) return next.changedFrom(previous, "tracks");
        for (String id : Config.IDS) if (p.containsKey(id) && next.changedFrom(previous, id)) return true;
        return false;
    }
    private static boolean relevant(Properties p, Config c) {
        if ("tracks".equals(p.getProperty("kind"))) return c.enabled("tracks");
        for (String id : Config.IDS) if (p.containsKey(id) && c.enabled(id)) return true;
        return false;
    }
    public static byte[] transform(Properties recipe, byte[] stock, Config c) throws IOException {
        require(Files.hash(stock).equals(recipe.getProperty("stockSha256")), "Wrong stock file");
        if ("tracks".equals(recipe.getProperty("kind"))) {
            if (!c.enabled("tracks")) return stock.clone();
            String text = new String(stock, "UTF-8");
            Pattern pattern = Pattern.compile("(?m)^(key\\s+)(34|35)(\\s+)(CHANNEL_UP|CHANNEL_DOWN)([ \\t]*(?:#.*)?)$");
            Matcher m = pattern.matcher(text); StringBuffer out = new StringBuffer(); int count = 0;
            while (m.find()) {
                String from = m.group(2).equals("34") ? "CHANNEL_UP" : "CHANNEL_DOWN";
                require(m.group(4).equals(from), "Track buttons are already remapped");
                String to = m.group(2).equals("34") ? "CHANNEL_DOWN" : "CHANNEL_UP";
                m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + m.group(2) + m.group(3) + to + m.group(5))); count++;
            }
            m.appendTail(out); require(count == 2, "Missing or duplicated steering-wheel track keys");
            return out.toString().getBytes("UTF-8");
        }
        byte[] result = stock.clone();
        for (String id : Config.IDS) {
            if (!c.enabled(id) || !recipe.containsKey(id)) continue;
            for (String operation : recipe.getProperty(id).split(";")) {
                String[] parts = operation.split(",");
                int at = Integer.parseInt(parts[0]); byte[] before = hex(parts[1]), after = hex(parts[2]);
                require(before.length == after.length && at >= 0 && at + before.length <= result.length, "Invalid patch extent");
                for (int i = 0; i < before.length; i++) require(result[at + i] == before[i], "Instruction does not match");
                System.arraycopy(after, 0, result, at, after.length);
            }
        }
        // Dalvik retains the original DEX SHA-1 after optimization. Recompute only
        // embedded Adler-32, retaining dependency checksums and register maps.
        int start = Integer.parseInt(recipe.getProperty("dexOffset")), size = Integer.parseInt(recipe.getProperty("dexSize"));
        require(start >= 0 && size >= 12 && start + size <= result.length, "Invalid DEX extent");
        Adler32 sum = new Adler32(); sum.update(result, start + 12, size - 12);
        long n = sum.getValue(); for (int i = 0; i < 4; i++) result[start + 8 + i] = (byte) (n >>> (8 * i));
        return result;
    }
    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
    private static void require(boolean condition, String message) throws IOException { if (!condition) throw new IOException(message); }
    private static final class Change {
        final File target, journal; final byte[] desired; final Properties values;
        Change(File target, byte[] desired, File journal, Properties values) { this.target = target; this.desired = desired; this.journal = journal; this.values = values; }
    }
}
