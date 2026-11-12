package com.tilepad.tweakcore;

import java.io.*;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Tweaks › Support's Check for Updates, root side. The app downloads a release's tilepad.zip and
 * its signature over Wi-Fi; this checks a root-owned copy against the release key and the installed
 * version, then leaves NEMESIS's install to a script that runs on its own, because the update stops
 * the app that asked for it, and an install needs a restart anyway (see docs/TWEAKS.md).
 */
public final class SelfUpdate {
    /** In Tilepad's records, which updates keep and uninstall removes. Root-only. */
    public static final File DIR = new File("/data/nemesis/backup/tilepad/update");
    public static final String ZIP = "tilepad.zip", SIGNATURE = "tilepad.zip.sig";
    static final String RESULT = "result.properties", LOG = "install.log", SCRIPT = "install.sh";
    /** A release zip is under a megabyte; anything near this isn't one. */
    static final long MAX_BYTES = 32L << 20;
    /** How long a started install blocks another, unless the head unit restarted since. */
    static final long INSTALL_MS = 15 * 60 * 1000L;
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

    /**
     * Copies the download into {@code dir} (root-owned, so the app can't change it after the check),
     * then requires the copy to be signed with {@code key}, to be the Tilepad package, and to be newer
     * than {@code installed}. Returns its version. Removes the copy when it fails a check.
     */
    static String stage(File zip, File signature, String installed, File dir, PublicKey key) throws Exception {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File copy = new File(dir, ZIP), sigCopy = new File(dir, SIGNATURE);
        try {
            copy(zip, copy); copy(signature, sigCopy);
            Signature check = Signature.getInstance("SHA256withRSA");
            check.initVerify(key);
            try (InputStream in = new FileInputStream(copy)) {
                byte[] b = new byte[65536]; for (int n; (n = in.read(b)) != -1;) check.update(b, 0, n);
            }
            if (!check.verify(Files.read(sigCopy)))
                throw new IOException("The download isn't signed with Tilepad's release key.");
            Properties d = descriptor(copy);
            String version = version(d.getProperty("package-version"));
            if (!"tilepad".equals(d.getProperty("id")) || version == null)
                throw new IOException("The download isn't a Tilepad package.");
            if (installed != null && compare(version, installed) <= 0)
                throw new IOException("Tilepad " + installed + " is already installed; the download is " + version + ".");
            return version;
        } catch (Exception e) {
            copy.delete(); sigCopy.delete();
            throw e;
        }
    }
    private static void copy(File from, File to) throws IOException {
        if (!from.isFile()) throw new IOException("The download is missing: " + from);
        if (from.length() > MAX_BYTES) throw new IOException("The download is too large to be a Tilepad release.");
        File temp = new File(to.getPath() + ".tmp");
        try (InputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(temp)) {
            byte[] b = new byte[65536]; long total = 0;
            for (int n; (n = in.read(b)) != -1;) {
                if ((total += n) > MAX_BYTES) throw new IOException("The download is too large to be a Tilepad release.");
                out.write(b, 0, n);
            }
            out.getFD().sync();
        }
        if (!temp.renameTo(to)) throw new IOException("Cannot stage " + to);
    }

    /** The [nemesis] section of a package's NEMESIS.data, unquoted. */
    static Properties descriptor(File zip) throws IOException {
        try (ZipFile z = new ZipFile(zip)) {
            ZipEntry e = z.getEntry("NEMESIS.data");
            if (e == null) throw new IOException("The download isn't a Tilepad package.");
            try (InputStream in = z.getInputStream(e)) { return descriptor(new String(read(in, 65536), "UTF-8")); }
        }
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
    /** Recorded before the script starts: the script only adds its outcome. */
    static void started(File dir, String version, String from, String boot, long now) throws IOException {
        Properties r = new Properties();
        r.setProperty("state", "installing"); r.setProperty("version", version); r.setProperty("from", from);
        r.setProperty("boot", boot); r.setProperty("started", Long.toString(now));
        Files.store(new File(dir, RESULT), r);
    }

    /**
     * Installs the staged package and restarts the head unit. Nothing in it lives in Tilepad's package
     * directory, which the install replaces. Its outcome lines come after the ones {@link #started}
     * wrote, so they win when the file is read back as properties.
     */
    static String script(File dir) {
        String d = dir.getPath();
        return "#!/system/bin/sh\n"
            + "# Written by Tweaks > Support (SelfUpdate.java). Runs on its own: the update stops the app that started it.\n"
            + "export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin\n"
            + "export LD_LIBRARY_PATH=/vendor/lib:/system/lib\n"
            + "D=" + d + "\n"
            + "/system/xbin/nemesis install --update \"$D/" + ZIP + "\" > \"$D/" + LOG + "\" 2>&1\n"
            + "CODE=$?\n"
            + "rm -f \"$D/" + ZIP + "\" \"$D/" + SIGNATURE + "\"\n"
            + "if [ \"$CODE\" = 0 ]; then STATE=installed; else STATE=failed; fi\n"
            + "echo \"state=$STATE\" >> \"$D/" + RESULT + "\"\n"
            + "echo \"code=$CODE\" >> \"$D/" + RESULT + "\"\n"
            + "sync\n"
            // The new APKs register at boot (docs/TWEAKS.md); a failure leaves the screen up to say why.
            + "[ \"$CODE\" = 0 ] || exit 0\n"
            + "sleep 2\n"
            + "/system/xbin/nemesis root 1000 -c 'setprop sys.powerctl reboot'\n";
    }

    /**
     * update.* lines for the Tweaks screen, from the last attempt: state (installing, installed, failed
     * or interrupted), version, started (ms), and on failure the end of NEMESIS's output. An install
     * still unfinished after INSTALL_MS counts as interrupted. Empty
     * when no update was started from Tweaks.
     */
    static Properties result(File dir, String boot, String installed, long now) {
        Properties out = new Properties();
        Properties r;
        try { r = Files.properties(new File(dir, RESULT)); } catch (IOException e) { return out; }
        String state = r.getProperty("state"), version = r.getProperty("version");
        if (state == null || version == null) return out;
        // Off before the script wrote its outcome, or the script died: the version on the unit says how far it got.
        long started = 0;
        try { started = Long.parseLong(r.getProperty("started", "0")); } catch (NumberFormatException ignored) { }
        if (state.equals("installing") && (!boot.equals(r.getProperty("boot")) || now - started >= INSTALL_MS))
            state = installed != null && version(version) != null && compare(installed, version) >= 0 ? "installed" : "interrupted";
        out.setProperty("update.state", state); out.setProperty("update.version", version);
        out.setProperty("update.started", r.getProperty("started", ""));
        if (state.equals("failed")) {
            String detail = "";
            try { detail = lastLines(new String(Files.read(new File(dir, LOG)), "UTF-8"), 3, 400); } catch (IOException ignored) { }
            out.setProperty("update.detail", detail.isEmpty() ? "NEMESIS exited " + r.getProperty("code", "?") + "." : detail);
        }
        return out;
    }
    static String lastLines(String text, int lines, int chars) {
        String[] all = text.trim().split("\n"); StringBuilder s = new StringBuilder();
        for (int i = Math.max(0, all.length - lines); i < all.length; i++) {
            String line = all[i].trim(); if (line.isEmpty()) continue;
            if (s.length() > 0) s.append(' '); s.append(line);
        }
        return s.length() > chars ? "…" + s.substring(s.length() - chars) : s.toString();
    }
    private SelfUpdate() { }
}
