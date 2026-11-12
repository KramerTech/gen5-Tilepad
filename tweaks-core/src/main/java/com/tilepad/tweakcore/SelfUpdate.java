package com.tilepad.tweakcore;

import java.io.*;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Tweaks › Package Manager's Check for Updates, root side. The app downloads, over Wi-Fi, Tilepad's release
 * (tilepad.zip and its signature) and the releases of other installed packages linked to a GitHub
 * repository. This checks root-owned copies (Tilepad's against the release key, the others against
 * GitHub's SHA-256) and the installed versions, then leaves NEMESIS's installs to a script that runs
 * on its own, because an update stops the app that asked for it, and restarts the head unit at the
 * end (see docs/TWEAKS.md).
 */
public final class SelfUpdate {
    /** In Tilepad's records, which updates keep and uninstall removes. Root-only. */
    public static final File DIR = new File("/data/nemesis/backup/tilepad/update");
    /** Other software Check for Updates follows: package id = GitHub owner/repository. Root-only. */
    public static final File SOURCES = new File("/data/nemesis/backup/tilepad/update-sources.properties");
    /** The GitHub users and organizations Configure Other Software has looked up, newest first (owners=a,b). Root-only. */
    public static final File OWNERS = new File("/data/nemesis/backup/tilepad/update-owners.properties");
    /** What the list holds until it's first changed. */
    static final String DEFAULT_OWNERS = "KramerTech,zpoon";
    /** NEMESIS's installed packages: a tree each, and a line each (id|name|locked|types; NEMESIS's docs add |package-version|short-desc). */
    public static final File APPS = new File("/data/nemesis/apps"), PACKAGES = new File("/data/nemesis/packages.db");
    public static final String TILEPAD = "tilepad", ZIP = "tilepad.zip", SIGNATURE = "tilepad.zip.sig";
    static final String RESULT = "result.properties", SCRIPT = "install.sh";
    /** A Tilepad release zip is under a megabyte; anything near this isn't one. */
    static final long MAX_BYTES = 32L << 20;
    /** Other packages can carry big APKs (SmartTube's is 25 MB). */
    public static final long MAX_PACKAGE_BYTES = 256L << 20;
    /** How long a started install blocks another, unless the head unit restarted since. */
    static final long INSTALL_MS = 30 * 60 * 1000L;
    /** The certificate that signs Tilepad's APKs (tools/signing-cert.sha256). Its key also signs each release's tilepad.zip. */
    static final String RELEASE_CERTIFICATE = "-----BEGIN CERTIFICATE-----\n"
        + "MIIC7zCCAdegAwIBAgIIKFUxfXgIldEwDQYJKoZIhvcNAQEMBQAwJTERMA8GA1UE\n"
        + "ChMIUGVyc29uYWwxEDAOBgNVBAMTB1RpbGVwYWQwIBcNMjYxMDI2MDk1NzA4WhgP\n"
        + "MjEyNjEwMDIwOTU3MDhaMCUxETAPBgNVBAoTCFBlcnNvbmFsMRAwDgYDVQQDEwdU\n"
        + "aWxlcGFkMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA3UE96LC5vuqW\n"
        + "gmKqom2R0o5H83MpAzH6Jf1bU/cS73qb74ZmfY3ZE2kJqlhLg1yzs3F/s747uOhp\n"
        + "vdFhkS+VQoEX85wYEZR+oEodQ0yZyKMAaM26USW2Wo3f5qsz6qlJkjCe9oh8bdrP\n"
        + "omXxov3diaWmN8M/GiRS0YdhbLonvmXOsdRaVeBTk5zGtuqVXCkJ1HU0Ksy8hlOw\n"
        + "FyAZKKcWEYrWl7qgduuLewLEWYNjLpHJ8IDeuF4+6FDAuks9wgddKSwHu9RPQGwM\n"
        + "QDsupcnhMi/AfB2uaP2DwzC0nBt+qjREY60frK9jwSIBYzNXXT9dUh/cXRbeUbVO\n"
        + "yGqp3vHNGQIDAQABoyEwHzAdBgNVHQ4EFgQUL8B1fEJRm4bE6G8QJHTvqaFdOxMw\n"
        + "DQYJKoZIhvcNAQEMBQADggEBAL7Vt3BkbL+MOX+8brhCsw0j+16IjGo+Elv7NObY\n"
        + "wL6Pd9tRAT8iF7rQyhGS7a+koVMvYqX1EfBjm1f8uAjT4edKbbidBhJA1xV/nZHn\n"
        + "BrTwD0ivMdDAVV0972ZRDhNnVW5a/xs/0NRCZNG3BFSH40Dvh2vSzlkAwl0s+Y6Z\n"
        + "E5P6/N+rySrXMBOkswXqiaBY9xS7VCyFWMsG2SPFHnjIIopXx7vypM28s6oNjLzK\n"
        + "BRuGeJ/AOLtR3sRMQE2xON6sOiM5AW8Yn0JZexYAlAiWsY/U6vqIUoMOHNkZ9chB\n"
        + "pj6QSbcvxLXP1nBQDFlS7EfOWG3a9Fj1lzACe0jKvNAg5N0=\n"
        + "-----END CERTIFICATE-----\n";

    static X509Certificate releaseCertificate() throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
            .generateCertificate(new ByteArrayInputStream(RELEASE_CERTIFICATE.getBytes("US-ASCII")));
    }

    /** "v1.2.0" or "1.2.0" as "1.2.0"; null for anything that isn't a plain release version. */
    public static String version(String tag) {
        String v = tag == null ? "" : tag.trim();
        if (v.startsWith("v")) v = v.substring(1);
        return v.matches("[0-9]{1,6}(\\.[0-9]{1,6}){0,3}") ? v : null;
    }
    /** Dotted release versions, number by number: 1.1.10 is newer than 1.1.9, and 1.2 equals 1.2.0. */
    public static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? Integer.parseInt(x[i]) : 0, q = i < y.length ? Integer.parseInt(y[i]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    /** The interface of the lowest-metric default route in /proc/net/route, or null when there is none. */
    public static String defaultRoute(String table) {
        String best = null; long metric = Long.MAX_VALUE;
        for (String line : table.split("\n")) {
            String[] f = line.trim().split("\\s+");
            // Iface Destination Gateway Flags RefCnt Use Metric Mask ...; flag 1 is RTF_UP.
            if (f.length < 8 || !f[1].equals("00000000") || !f[7].equals("00000000")) continue;
            try {
                if ((Integer.parseInt(f[3], 16) & 1) == 0) continue;
                long m = Long.parseLong(f[6]);
                if (m < metric) { metric = m; best = f[0]; }
            } catch (NumberFormatException ignored) { }
        }
        return best;
    }

    /** NEMESIS's rule for a package id: letters, digits, . _ -, no leading . or -, no "..". */
    public static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_][A-Za-z0-9._-]{0,63}") && !id.contains("..");
    }
    /** GitHub's owner/repository. */
    public static boolean validRepo(String repo) {
        return repo != null && repo.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9._-]{1,100}") && !repo.endsWith("/.") && !repo.endsWith("/..");
    }

    /** One line of packages.db. */
    public static final class Package {
        public final String id, name, version; public final boolean locked;
        Package(String id, String name, String version, boolean locked) { this.id = id; this.name = name; this.version = version; this.locked = locked; }
    }
    /** packages.db, in its order; version only when the line has one (1.16.7's don't). The locked core packages (usb-handler, the update monitor) only update with NEMESIS. */
    public static List<Package> packages(String db) {
        List<Package> out = new ArrayList<>();
        for (String line : db.split("\n")) {
            String[] f = line.trim().split("\\|", -1);
            if (f.length >= 3 && validId(f[0])) out.add(new Package(f[0], f[1].isEmpty() ? f[0] : f[1], f.length > 4 ? version(f[4]) : null, f[2].equals("1")));
        }
        return out;
    }
    static Package installedPackage(File db, String id) {
        try {
            for (Package p : packages(new String(Files.read(db), "UTF-8"))) if (p.id.equals(id)) return p;
        } catch (IOException ignored) { }
        return null;
    }
    /**
     * package-version of an installed package, from its own tree: NEMESIS copies that before running
     * postinstall, so it is right even when a postinstall restarted the head unit before NEMESIS finished.
     */
    public static String installedVersion(File apps, String id) {
        try {
            return version(descriptor(new String(Files.read(new File(new File(apps, id), "NEMESIS.data")), "UTF-8")).getProperty("package-version"));
        } catch (IOException e) { return null; }
    }

    /** Links an installed package to the repository whose releases update it, or with a null repo, unlinks it. */
    static void setSource(File sources, File apps, File db, String id, String repo) throws IOException {
        if (!validId(id)) throw new IOException("That isn't a NEMESIS package id.");
        Properties p = Files.properties(sources);
        if (repo == null) p.remove(id);
        else {
            if (id.equals(TILEPAD)) throw new IOException("Tilepad already updates from its own repository.");
            if (!validRepo(repo)) throw new IOException("That isn't a GitHub repository (owner/name).");
            // A package that isn't installed yet can be linked too: Package Manager installs it fresh.
            Package installed = installedPackage(db, id);
            if (installed != null && installed.locked) throw new IOException(installed.name + " is part of NEMESIS and updates with it.");
            p.setProperty(id, repo);
        }
        Files.store(sources, p);
    }

    /**
     * Whether Package Manager's delete can uninstall the package: refuses Tilepad (its USB uninstall
     * bundle restores what it changed first) and NEMESIS's own. False when it isn't installed, so only
     * its link goes.
     */
    static boolean uninstallable(File db, String id) throws IOException {
        if (!validId(id)) throw new IOException("That isn't a NEMESIS package id.");
        if (id.equals(TILEPAD)) throw new IOException("Tilepad can't uninstall itself from Package Manager. Use the USB uninstall bundle from its GitHub release.");
        Package installed = installedPackage(db, id);
        if (installed != null && installed.locked) throw new IOException(installed.name + " is part of NEMESIS and can't be uninstalled.");
        return installed != null;
    }

    /** The looked-up owners as the app shows them, comma-separated. */
    static String owners(File file) throws IOException {
        return file.isFile() ? Files.properties(file).getProperty("owners", "") : DEFAULT_OWNERS;
    }
    /** Replaces the looked-up owners (the app adds and removes); each must be a GitHub user or organization name. */
    static void setOwners(File file, String list) throws IOException {
        StringBuilder kept = new StringBuilder(); int count = 0;
        for (String name : list.split(",")) {
            if (name.trim().isEmpty()) continue;
            if (!validRepo(name.trim() + "/x")) throw new IOException("That isn't a GitHub user or organization: " + name.trim());
            if (count++ < MAX_OWNERS) kept.append(kept.length() == 0 ? "" : ",").append(name.trim());
        }
        Properties p = new Properties();
        p.setProperty("owners", kept.toString());
        Files.store(file, p);
    }
    static final int MAX_OWNERS = 20;

    /** One package to install: what the app downloaded and how to check it. */
    static final class Item {
        final String id, check, inner; final File download;
        String version, from, name; boolean reboots; final List<String> depends = new ArrayList<>();
        /**
         * check: Tilepad's signature file, or GitHub's SHA-256 of the download (hex). inner: the
         * package's path inside the download when that is a USB bundle, else null.
         */
        Item(String id, File download, String check, String inner) { this.id = id; this.download = download; this.check = check; this.inner = inner; }
    }

    /**
     * Copies the download into {@code dir} (root-owned, so the app can't change it after the check),
     * requires the copy to be Tilepad's signed release or to match GitHub's checksum, takes the package
     * out of a bundle, then requires it to be the package named and newer than {@code installed}.
     * Leaves dir/ID.zip and fills in the item; removes everything it made when a check fails.
     */
    static void stage(Item item, File dir, String installed, PublicKey key) throws Exception {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        boolean tilepad = item.id.equals(TILEPAD);
        File out = new File(dir, item.id + ".zip"), download = new File(dir, item.id + ".download"), sig = new File(dir, item.id + ".zip.sig");
        long max = tilepad ? MAX_BYTES : MAX_PACKAGE_BYTES;
        try {
            copy(item.download, download, max);
            if (tilepad) {
                copy(new File(item.check), sig, 4096);
                Signature check = Signature.getInstance("SHA256withRSA");
                check.initVerify(key);
                try (InputStream in = new FileInputStream(download)) {
                    byte[] b = new byte[65536]; for (int n; (n = in.read(b)) != -1;) check.update(b, 0, n);
                }
                if (!check.verify(Files.read(sig))) throw new IOException("The download isn't signed with Tilepad's release key.");
            } else if (!Files.hash(download).equalsIgnoreCase(item.check))
                throw new IOException("The download of " + item.id + " doesn't match GitHub's checksum. Check again to retry.");
            if (item.inner == null) {
                if (!download.renameTo(out)) throw new IOException("Cannot stage " + out);
            } else {
                extract(download, item.inner, out, max);
                download.delete();
            }
            Properties d = descriptor(out);
            String version = version(d.getProperty("package-version"));
            if (!item.id.equals(d.getProperty("id")) || version == null)
                throw new IOException(tilepad ? "The download isn't a Tilepad package." : "The download isn't the " + item.id + " package.");
            String name = d.getProperty("name", tilepad ? "Tilepad" : item.id);
            if (installed != null && compare(version, installed) <= 0)
                throw new IOException(name + " " + installed + " is already installed; the download is " + version + ".");
            item.version = version; item.from = installed; item.name = name; item.reboots = reboots(out, d);
            for (String need : d.getProperty("depends", "").split(",")) {
                String id = need.trim(); int at = id.indexOf('@');
                if (at >= 0) id = id.substring(0, at);
                if (validId(id)) item.depends.add(id);
            }
        } catch (Exception e) {
            out.delete(); download.delete(); sig.delete();
            throw e;
        }
    }
    private static void copy(File from, File to, long max) throws IOException {
        if (!from.isFile()) throw new IOException("The download is missing: " + from);
        try (InputStream in = new FileInputStream(from)) { copy(in, to, max); }
    }
    private static void copy(InputStream in, File to, long max) throws IOException {
        File temp = new File(to.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            byte[] b = new byte[65536]; long total = 0;
            for (int n; (n = in.read(b)) != -1;) {
                if ((total += n) > max) throw new IOException("The download is too large to be a NEMESIS package.");
                out.write(b, 0, n);
            }
            out.getFD().sync();
        } catch (IOException e) { temp.delete(); throw e; }
        if (!temp.renameTo(to)) { temp.delete(); throw new IOException("Cannot stage " + to); }
    }
    /** The package inside a USB bundle. */
    private static void extract(File bundle, String path, File to, long max) throws IOException {
        if (!ZipProbe.inner(path)) throw new IOException("The download isn't a NEMESIS package.");
        try (ZipFile z = new ZipFile(bundle)) {
            ZipEntry e = z.getEntry(path);
            if (e == null) throw new IOException("The download has no " + path + ".");
            try (InputStream in = z.getInputStream(e)) { copy(in, to, max); }
        }
    }
    /**
     * Whether the package's install hooks restart the head unit themselves (Evergreen's postinstall
     * does). Nothing queued after such a package would run, so it goes last.
     */
    static boolean reboots(File zip, Properties d) {
        try (ZipFile z = new ZipFile(zip)) {
            for (String hook : new String[]{"preinstall", "postinstall"}) {
                String path = d.getProperty(hook);
                ZipEntry e = path == null ? null : z.getEntry(path.startsWith("./") ? path.substring(2) : path);
                if (e == null || e.getSize() > 1 << 20) continue;
                try (InputStream in = z.getInputStream(e)) { if (reboots(new String(read(in, 1 << 20), "UTF-8"))) return true; }
            }
        } catch (IOException ignored) { }
        return false;
    }
    private static final Pattern REBOOT = Pattern.compile(
        "(^|[;&|]|\\bthen\\b|\\bdo\\b|\\belse\\b)\\s*(exec\\s+)?(/system/bin/)?(reboot|svc\\s+power\\s+reboot)(\\s|;|$)|sys\\.powerctl");
    /** A reboot command in a script, not one mentioned in a message or comment. */
    static boolean reboots(String script) {
        for (String line : script.split("\n")) {
            String code = line.trim();
            if (code.startsWith("#")) continue;
            // Drop quoted text: messages often tell the user to reboot.
            code = code.replaceAll("\"[^\"]*\"|'[^']*'", "\"\"");
            if (REBOOT.matcher(code).find()) return true;
        }
        return false;
    }

    /**
     * Install order: other packages first, then Tilepad, then any that restart the head unit
     * themselves; a package after the ones in the batch it depends on.
     */
    static List<Item> order(List<Item> items) {
        List<Item> pending = new ArrayList<>(items);
        Collections.sort(pending, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) { return group(a) - group(b); }
            private int group(Item i) { return i.reboots ? 2 : i.id.equals(TILEPAD) ? 1 : 0; }
        });
        List<Item> out = new ArrayList<>();
        while (!pending.isEmpty()) {
            Item next = pending.get(0);
            for (Item i : pending) {
                boolean waits = false;
                for (Item other : pending) waits |= other != i && i.depends.contains(other.id);
                if (!waits) { next = i; break; }
            }
            out.add(next); pending.remove(next);
        }
        return out;
    }

    /** The [nemesis] section of a package's NEMESIS.data, unquoted; empty when the file isn't a package. */
    static Properties descriptor(File zip) throws IOException {
        try (ZipFile z = new ZipFile(zip)) {
            ZipEntry e = z.getEntry("NEMESIS.data");
            if (e == null) return new Properties();
            try (InputStream in = z.getInputStream(e)) { return descriptor(new String(read(in, 65536), "UTF-8")); }
        } catch (java.util.zip.ZipException notAZip) { return new Properties(); }
    }
    static Properties descriptor(String text) {
        Properties p = new Properties(); boolean inside = false;
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.startsWith("[")) { inside = line.equals("[nemesis]"); continue; }
            int eq = line.indexOf('=');
            if (!inside || eq <= 0 || line.startsWith("#") || line.startsWith(";")) continue;
            String value = line.substring(eq + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length() - 1);
            p.setProperty(line.substring(0, eq).trim(), value);
        }
        return p;
    }
    private static byte[] read(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[4096];
        for (int n; (n = in.read(b)) != -1;) { if (out.size() + n > limit) throw new IOException("Package metadata too large"); out.write(b, 0, n); }
        return out.toByteArray();
    }

    /** Whether an install this boot started recently enough that it may still be running. */
    static boolean installing(File dir, String boot, long now) throws IOException {
        Properties r = Files.properties(new File(dir, RESULT));
        long started = 0;
        try { started = Long.parseLong(r.getProperty("started", "0")); } catch (NumberFormatException ignored) { }
        return "installing".equals(r.getProperty("state")) && boot.equals(r.getProperty("boot")) && now - started < INSTALL_MS;
    }
    /** Recorded before the script starts: the script only adds each package's outcome. */
    static void started(File dir, List<Item> items, String boot, long now) throws IOException {
        Properties r = new Properties();
        StringBuilder ids = new StringBuilder();
        for (Item i : items) {
            ids.append(ids.length() == 0 ? "" : ",").append(i.id);
            r.setProperty("item." + i.id + ".version", i.version); r.setProperty("item." + i.id + ".from", i.from == null ? "" : i.from);
            r.setProperty("item." + i.id + ".name", i.name);
        }
        r.setProperty("state", "installing"); r.setProperty("items", ids.toString());
        r.setProperty("boot", boot); r.setProperty("started", Long.toString(now));
        Files.store(new File(dir, RESULT), r);
    }
    /** Removes what an earlier install left: staged packages it never reached, when the head unit restarted mid-way. */
    static void tidy(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            String n = f.getName();
            if (n.endsWith(".zip") || n.endsWith(".sig") || n.endsWith(".download") || n.endsWith(".tmp")) f.delete();
        }
    }

    /**
     * Installs the staged packages in order and restarts the head unit if any of them installed.
     * Nothing in it lives in a package directory an install replaces. Each package's outcome lines
     * come after the ones {@link #started} wrote, so they win when the file is read back as properties.
     */
    static String script(File dir, List<Item> items) {
        StringBuilder s = new StringBuilder("#!/system/bin/sh\n"
            + "# Written by Tweaks > Package Manager (SelfUpdate.java). Runs on its own: an update stops the app that started it.\n"
            + "export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin\n"
            + "export LD_LIBRARY_PATH=/vendor/lib:/system/lib\n"
            + "D=" + dir.getPath() + "\n"
            + "R=\"$D/" + RESULT + "\"\n"
            + "ANY=0\n"
            + "update_one() {\n"
            + "    /system/xbin/nemesis install $2 \"$D/$1.zip\" > \"$D/$1.log\" 2>&1\n"
            + "    CODE=$?\n"
            + "    rm -f \"$D/$1.zip\" \"$D/$1.zip.sig\"\n"
            + "    if [ \"$CODE\" = 0 ]; then ANY=1; STATE=installed; else STATE=failed; fi\n"
            + "    echo \"item.$1.state=$STATE\" >> \"$R\"\n"
            + "    echo \"item.$1.code=$CODE\" >> \"$R\"\n"
            + "    sync\n"
            + "}\n");
        // Ids are NEMESIS ids (validId): nothing in them the shell would read.
        // A package new to this head unit installs without --update.
        for (Item i : items) s.append("update_one ").append(i.id).append(i.from == null ? "" : " --update").append('\n');
        return s.append("echo state=done >> \"$R\"\n"
            + "sync\n"
            // New APKs register at boot (docs/TWEAKS.md); when nothing installed, the screen stays up to say why.
            + "[ \"$ANY\" = 1 ] || exit 0\n"
            + "sleep 2\n"
            + "/system/xbin/nemesis root 1000 -c 'setprop sys.powerctl reboot'\n").toString();
    }

    /**
     * update.* lines for the Tweaks screen, from the last install: update.state (installing or done),
     * update.started (ms), update.items (ids, in install order), and for each id its name, version,
     * from, state (installing, installed, failed or interrupted), current (whether that version or a
     * newer one is installed now), installed (the version now, empty when none) and, on failure, the end of NEMESIS's output. A package with no
     * outcome counts as interrupted once the head unit restarted or INSTALL_MS passed. Empty when no
     * update was started from Tweaks.
     */
    static Properties result(File dir, File apps, String boot, long now) {
        Properties out = new Properties();
        Properties r;
        try { r = Files.properties(new File(dir, RESULT)); } catch (IOException e) { return out; }
        String items = r.getProperty("items");
        if (r.getProperty("state") == null || items == null) return out;
        long started = 0;
        try { started = Long.parseLong(r.getProperty("started", "0")); } catch (NumberFormatException ignored) { }
        boolean running = "installing".equals(r.getProperty("state")) && boot.equals(r.getProperty("boot")) && now - started < INSTALL_MS;
        boolean any = false;
        for (String id : items.split(",")) {
            if (!validId(id)) continue;
            String version = r.getProperty("item." + id + ".version", ""), installed = installedVersion(apps, id);
            boolean current = installed != null && version(version) != null && compare(installed, version) >= 0;
            String state = r.getProperty("item." + id + ".state");
            // Off before the script wrote this one's outcome, or a package restarted the head unit: the installed version says how far it got.
            if (state == null) state = running ? "installing" : current ? "installed" : "interrupted";
            any |= state.equals("installing");
            String p = "update." + id + ".";
            out.setProperty(p + "name", r.getProperty("item." + id + ".name", id)); out.setProperty(p + "version", version);
            out.setProperty(p + "from", r.getProperty("item." + id + ".from", "")); out.setProperty(p + "state", state);
            out.setProperty(p + "current", Boolean.toString(current));
            // Empty when the package is gone: NEMESIS removes the old version before the new one's preinstall, which can fail.
            out.setProperty(p + "installed", installed == null ? "" : installed);
            if (state.equals("failed")) {
                String detail = "";
                try { detail = lastLines(new String(Files.read(new File(dir, id + ".log")), "UTF-8"), 3, 400); } catch (IOException ignored) { }
                out.setProperty(p + "detail", detail.isEmpty() ? "NEMESIS exited " + r.getProperty("item." + id + ".code", "?") + "." : detail);
            }
        }
        out.setProperty("update.items", items); out.setProperty("update.state", any ? "installing" : "done");
        out.setProperty("update.started", r.getProperty("started", ""));
        return out;
    }
    /** The end of NEMESIS's output, without its timestamps and hook lines. */
    static String lastLines(String text, int lines, int chars) {
        List<String> all = new ArrayList<>();
        for (String raw : text.split("\n")) {
            String line = raw.trim().replaceFirst("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} ", "");
            if (!line.isEmpty() && !line.startsWith("hook ")) all.add(line);
        }
        StringBuilder s = new StringBuilder();
        for (int i = Math.max(0, all.size() - lines); i < all.size(); i++) {
            String line = all.get(i);
            if (s.length() > 0) s.append(' '); s.append(line);
        }
        return s.length() > chars ? "…" + s.substring(s.length() - chars) : s.toString();
    }
    private SelfUpdate() { }
}
