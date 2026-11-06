import com.tilepad.tweakcore.*;
import java.io.*;
import java.util.*;

/** Real captured files, production patch/journal code, all sixteen switch combinations. */
public final class CaptureCheck {
    public static void main(String[] args) throws Exception {
        final File system = new File(args[0]), work = new File(args[1]), recipes = new File(args[2]);
        final File root = new File(work, "system"), state = new File(work, "state");
        List<Properties> profiles = new ArrayList<>();
        File[] files = recipes.listFiles(); Arrays.sort(files);
        Map<String, byte[]> stock = new HashMap<>();
        // The track recipe names no file; on the car it targets the loaded layout, here the IONIQ 5's.
        final String layout = "/system/usr/keylayout/daudio_wp_daudioplus_neev23_base.kl";
        for (File f : files) if (f.getName().endsWith(".properties")) {
            Properties p = Files.properties(f);
            // Another release's recipe for the same file: not this capture's.
            if (p.getProperty("path") != null && !"tracks".equals(p.getProperty("kind"))
                && !p.getProperty("stockSha256").equals(Files.hash(Files.read(new File(system, p.getProperty("path").substring("/system/".length())))))) continue;
            profiles.add(p);
            if ("tracks".equals(p.getProperty("kind"))) {
                p.setProperty("path", layout);
                p.setProperty("stockSha256", Files.hash(Files.read(new File(system, layout.substring("/system/".length())))));
            }
            for (String key : new String[]{"path", "apkPath"}) {
                String path = p.getProperty(key); if (path == null) continue;
                byte[] data = Files.read(new File(system, path.substring("/system/".length())));
                stock.put(path, data); Files.write(new File(root, path.substring(1)), data);
            }
        }
        PatchManager manager = new PatchManager(state, recipes, new PatchManager.SystemOps() {
            @Override public File file(String path) { return new File(root, path.substring(1)); }
            @Override public void writable() { }
            @Override public void finish() { }
            @Override public void replace(File target, byte[] data) throws Exception { Files.write(target, data); }
            @Override public List<String> layouts() { return Collections.singletonList(layout); }
        });
        String[] ids = {"consent", "nav", "popover", "tracks"};
        for (int mask = 0; mask < 16; mask++) {
            Config config = new Config(new Properties());
            for (int bit = 0; bit < 4; bit++) config.set(ids[bit], Boolean.toString((mask & 1 << bit) != 0));
            manager.apply(config, "capture-test");
            for (Properties p : profiles) {
                String path = p.getProperty("path");
                byte[] wanted = PatchManager.transform(p, stock.get(path), config);
                if (!Arrays.equals(wanted, Files.read(new File(root, path.substring(1))))) throw new AssertionError("Wrong bytes: " + mask + " " + path);
                if (mask == 2 && path.endsWith("RightScreenHome.odex")) {
                    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
                    String md5 = new java.math.BigInteger(1, digest.digest(wanted)).toString(16);
                    if (!md5.equals("e8eac4e6a0f33dc2e4cca904298a8682")) throw new AssertionError("Nav port differs from verified local implementation");
                }
                if (mask == 15 && path.endsWith(".odex")) Files.write(new File(work, "patched/" + new File(path).getName()), wanted);
            }
        }
        manager.apply(new Config(new Properties()), "capture-test");
        for (Map.Entry<String, byte[]> entry : stock.entrySet()) if (!Arrays.equals(entry.getValue(), Files.read(new File(root, entry.getKey().substring(1))))) throw new AssertionError("Restore mismatch");
        System.out.println("PASS: all 16 static-tweak combinations on actual captured files; exact original bytes restored.");
    }
}
