package com.tilepad.tweaks;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.StatFs;
import com.tilepad.tweakcore.SelfUpdate;
import com.tilepad.tweakcore.ZipProbe;
import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.zip.ZipException;
import javax.net.ssl.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Update Manager's Check for Updates, app side, over Wi-Fi only: finds the latest GitHub release of Tilepad
 * and of each package linked to a repository, reads which package each release zip holds (a few
 * small range requests, see ZipProbe), and downloads the chosen ones. The root command checks and
 * installs the downloads (SelfUpdate).
 */
final class Updater {
    static final String TILEPAD_REPO = "KramerTech/gen5-Tilepad";
    private static final String API = "https://api.github.com/";
    private static final int CONNECT_MS = 15000, READ_MS = 30000, MAX_JSON = 1 << 20;
    /** The unit reads 2015 until it has the real time, and certificates can't be checked before then. */
    private static final long CLOCK_SET = 1761955200000L; // 2025-11-01
    private static SSLSocketFactory factory;

    /** One package's latest release, against what's installed. */
    static final class Offer {
        String id, name, repo, installed;
        /** The release's package-version; null when it couldn't be checked, and problem says why. installed is null when it isn't installed here. */
        String version, problem;
        /** The release asset holding the package, GitHub's SHA-256 of it, and the package's path inside it when it is a USB bundle. */
        String url, digest, inner;
        /** Tilepad only: its release signature. */
        String signature;
        long size;
        String tag = "", notes = "";
        /** Not installed here: the release installs fresh. */
        boolean fresh() { return installed == null && !id.equals(SelfUpdate.TILEPAD); }
        boolean newer() {
            if (version == null) return false;
            if (fresh()) return SelfUpdate.version(version) != null;
            return SelfUpdate.version(installed) != null && SelfUpdate.compare(version, installed) > 0;
        }
        /** Newer (or not installed yet), and the release has what the root side checks it against. */
        boolean installable() { return newer() && url != null && digest != null && (!id.equals(SelfUpdate.TILEPAD) || signature != null); }
    }
    /** A Wi-Fi or clock problem: nothing is downloaded. settings: whether Wi-Fi settings can fix it. */
    static final class NetworkProblem extends IOException {
        final boolean settings;
        NetworkProblem(String message, boolean settings) { super(message); this.settings = settings; }
    }
    /** GitHub answered, but not with what was needed: says so for that package without stopping the rest. */
    static final class Answer extends IOException { Answer(String message) { super(message); } }
    interface Progress { void at(int index, int percent); }

    /** Why nothing may be downloaded now, or null. Checked before every request. */
    static NetworkProblem networkProblem(Context c) {
        if (System.currentTimeMillis() < CLOCK_SET)
            return new NetworkProblem("The head unit's clock isn't set yet, so GitHub's certificate can't be checked. Try again once the clock shows the right time.", false);
        // The SDK emulator has no Wi-Fi, only its one emulated link.
        if (Build.HARDWARE.contains("goldfish") || Build.HARDWARE.contains("ranchu")) return null;
        WifiManager wifi = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi == null || !wifi.isWifiEnabled()) {
            PackageManager pm = c.getPackageManager();
            // Stock US units: the radio only runs the CarPlay/Android Auto hotspot until Tilepad's Wi-Fi tile allows more.
            if (pm.hasSystemFeature("com.hkmc.software.wifi.module") && !pm.hasSystemFeature("android.hardware.wifi"))
                return new NetworkProblem("This head unit's Wi-Fi is set up only for wireless CarPlay and Android Auto. To let it join a network, hold the ★ button and tap Tilepad's Wi-Fi tile (it needs a restart).", false);
            return new NetworkProblem("Wi-Fi is off. Turn it on and connect to a network (a phone hotspot works), then check again. Tilepad only downloads over Wi-Fi, never the car's built-in modem.", true);
        }
        NetworkInfo active = ((ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetworkInfo();
        if (active == null || !active.isConnected() || active.getType() != ConnectivityManager.TYPE_WIFI)
            return new NetworkProblem("Wi-Fi isn't connected to a network. Connect to one (a phone hotspot works), then check again.", true);
        String route = null;
        try { route = SelfUpdate.defaultRoute(new String(com.tilepad.tweakcore.Files.read(new File("/proc/net/route")), "UTF-8")); } catch (IOException ignored) { }
        if (route == null || !route.startsWith("wlan"))
            return new NetworkProblem("Wi-Fi is connected, but the head unit is sending internet traffic through the car's modem, so Tilepad won't download. Reconnect Wi-Fi and check again.", true);
        return null;
    }

    /** NEMESIS's installed packages (packages.db is world-readable), each with the version its own tree says. */
    static Map<String, SelfUpdate.Package> installed() {
        Map<String, SelfUpdate.Package> out = new LinkedHashMap<>();
        try {
            for (SelfUpdate.Package p : SelfUpdate.packages(new String(com.tilepad.tweakcore.Files.read(SelfUpdate.PACKAGES), "UTF-8"))) out.put(p.id, p);
        } catch (IOException ignored) { }
        return out;
    }
    static String installedVersion(Map<String, SelfUpdate.Package> installed, String id) {
        String v = SelfUpdate.installedVersion(SelfUpdate.APPS, id);
        SelfUpdate.Package p = installed.get(id);
        return v != null ? v : p == null ? null : p.version;
    }

    /**
     * Tilepad's latest release, then each linked package's ({@code sources}: id = owner/repo), in id
     * order. A package GitHub has nothing for says why in its problem; losing the network stops the check.
     */
    static List<Offer> check(Context c, String current, Properties sources) throws Exception {
        List<Offer> out = new ArrayList<>();
        out.add(tilepad(c, current));
        Map<String, SelfUpdate.Package> installed = installed();
        Map<String, JSONObject> releases = new HashMap<>();
        for (String id : new TreeSet<>(sources.stringPropertyNames())) {
            String repo = sources.getProperty(id);
            Offer o = null; String problem = null;
            try {
                JSONObject release = releases.get(repo);
                if (release == null) releases.put(repo, release = latest(c, current, repo));
                List<Offer> found = packages(c, current, repo, release, id);
                if (found.isEmpty()) problem = "The latest release of " + repo + " has no " + id + " package.";
                else o = found.get(0);
            } catch (Answer | JSONException | ZipException e) { problem = e instanceof Answer ? e.getMessage() : "GitHub's answer for " + repo + " couldn't be read."; }
            if (o == null) {
                o = new Offer(); o.id = id; o.repo = repo; o.problem = problem;
                SelfUpdate.Package p = installed.get(id);
                o.name = p == null ? id : p.name;
            }
            o.installed = installedVersion(installed, id);
            out.add(o);
        }
        return out;
    }
    private static Offer tilepad(Context c, String current) throws Exception {
        JSONObject release = latest(c, current, TILEPAD_REPO);
        Offer o = new Offer();
        o.id = SelfUpdate.TILEPAD; o.name = "Tilepad"; o.repo = TILEPAD_REPO; o.installed = current;
        release(o, release);
        o.version = SelfUpdate.version(o.tag);
        if (o.version == null) throw new Answer("GitHub's latest Tilepad release has no version number.");
        JSONArray assets = release.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.optString("name").equals(SelfUpdate.ZIP)) asset(o, a);
            else if (a.optString("name").equals(SelfUpdate.SIGNATURE)) o.signature = a.optString("browser_download_url");
        }
        return o;
    }
    private static JSONObject latest(Context c, String current, String repo) throws Exception {
        try { return json(c, current, "repos/" + repo + "/releases/latest"); }
        catch (Answer missing) { throw new Answer(repo + " has no published release."); }
    }
    private static void release(Offer o, JSONObject release) {
        o.tag = release.optString("tag_name");
        o.notes = notes(release.optString("body"));
    }
    private static void asset(Offer o, JSONObject a) {
        o.url = a.optString("browser_download_url"); o.size = a.optLong("size");
        String digest = a.optString("digest");
        o.digest = digest.startsWith("sha256:") && digest.length() == 71 ? digest.substring(7) : null;
    }

    /** A GitHub user's or organization's public repositories, for picking one to link: {login as GitHub spells it, repositories}. */
    static final class Account { String login; List<Repos.Listed> repos = new ArrayList<>(); }
    private static final int PAGE = 100, MAX_PAGES = 3;
    /**
     * The account's repositories, most recently pushed first, except Tilepad's own. Which ones are
     * NEMESIS packages comes from one search for "nemesis" in their names, descriptions, topics and
     * READMEs, forks included: reading each repository's releases would take a request per
     * repository, and GitHub allows 60 an hour without signing in. If the search fails, nothing is marked.
     */
    static Account account(Context c, String current, String owner) throws Exception {
        if (!SelfUpdate.validRepo(owner + "/x")) throw new Answer("That isn't a GitHub user or organization name.");
        Account a = new Account();
        try { a.login = json(c, current, "users/" + owner).optString("login", owner); }
        catch (Answer missing) { throw new Answer("GitHub has no user or organization called " + owner + "."); }
        if (!SelfUpdate.validRepo(a.login + "/x")) a.login = owner;
        Set<String> nemesis = new HashSet<>();
        try {
            JSONArray hits = json(c, current, "search/repositories?per_page=100&q=nemesis+user:" + a.login + "+fork:true+in:name,description,topics,readme").optJSONArray("items");
            for (int i = 0; hits != null && i < hits.length(); i++) nemesis.add(hits.getJSONObject(i).optString("name"));
        } catch (NetworkProblem e) { throw e; }
        catch (Exception unmarked) { }  // e.g. the search's own limit, 10 a minute: list without marks
        for (int page = 1; page <= MAX_PAGES; page++) {
            JSONArray list = array(c, current, "users/" + a.login + "/repos?type=owner&sort=pushed&per_page=" + PAGE + "&page=" + page);
            for (int i = 0; i < list.length(); i++) {
                JSONObject r = list.getJSONObject(i);
                Repos.Listed l = new Repos.Listed();
                l.name = r.optString("name");
                if (!SelfUpdate.validRepo(a.login + "/" + l.name) || (a.login + "/" + l.name).equalsIgnoreCase(TILEPAD_REPO)) continue;
                l.description = r.isNull("description") ? "" : r.optString("description").trim();
                l.nemesis = nemesis.contains(l.name);
                a.repos.add(l);
            }
            if (list.length() < PAGE) break;
        }
        return a;
    }
    /** A repository and the packages in its latest release, before linking: {owner/repo as GitHub spells it, packages}. */
    static final class Repo { String name; List<Offer> packages; }
    static Repo find(Context c, String current, String owner, String name) throws Exception {
        String asked = owner + "/" + name;
        if (!SelfUpdate.validRepo(asked)) throw new Answer("That isn't a GitHub user and repository name.");
        Repo r = new Repo();
        try { r.name = json(c, current, "repos/" + asked).optString("full_name", asked); }
        catch (Answer missing) { throw new Answer("GitHub has no public repository " + asked + "."); }
        if (!SelfUpdate.validRepo(r.name)) r.name = asked;
        r.packages = packages(c, current, r.name, latest(c, current, r.name), null);
        Map<String, SelfUpdate.Package> installed = installed();
        for (Offer o : r.packages) o.installed = installedVersion(installed, o.id);
        return r;
    }

    /**
     * The packages in a release's zip assets, or only {@code want}'s. Package zips are read before USB
     * bundles, which usually wrap the same package; a bundle whose package is compressed is only read
     * when nothing else was found, since that means downloading most of it.
     */
    private static List<Offer> packages(Context c, String current, String repo, JSONObject release, String want) throws Exception {
        List<JSONObject> zips = new ArrayList<>();
        JSONArray assets = release.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i); String n = a.optString("name").toLowerCase(Locale.US);
            if (n.endsWith(".zip") && !n.contains("uninstall") && a.optLong("size") > 0) zips.add(a);
        }
        Collections.sort(zips, new Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) { return usb(a) - usb(b); }
            private int usb(JSONObject a) { return a.optString("name").toLowerCase(Locale.US).contains("usb") ? 1 : 0; }
        });
        List<Offer> out = new ArrayList<>();
        for (JSONObject a : zips) {
            ZipProbe.Found f;
            try { f = ZipProbe.probe(new Remote(c, current, a.optString("browser_download_url"), a.optLong("size")), out.isEmpty()); }
            catch (ZipException notAPackage) { continue; }
            if (f == null) continue;
            String id = f.descriptor.getProperty("id"), version = SelfUpdate.version(f.descriptor.getProperty("package-version"));
            if (!SelfUpdate.validId(id) || version == null || (want != null && !want.equals(id))) continue;
            boolean seen = false;
            for (Offer o : out) seen |= o.id.equals(id);
            if (seen) continue;
            Offer o = new Offer();
            o.id = id; o.name = f.descriptor.getProperty("name", id); o.repo = repo; o.version = version; o.inner = f.inner;
            release(o, release); asset(o, a);
            out.add(o);
            if (want != null) break;
        }
        return out;
    }

    /**
     * The offers' release assets (and Tilepad's signature), in a folder of the app's own that root can
     * read (it has no DAC override) and no other app can write. Each is checked against GitHub's
     * SHA-256 here too. Returns the root command's arguments: ID FILE CHECK INNER for each.
     */
    static List<String> download(Context c, List<Offer> offers, String current, Progress progress) throws Exception {
        File dir = new File(c.getFilesDir(), "update");
        clear(c);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Can't make room for the download.");
        for (File d : new File[]{c.getFilesDir(), dir}) d.setExecutable(true, false);
        // This copy, root's copy, and the package taken out of a bundle.
        long need = 16 << 20;
        for (Offer o : offers) need += 3 * o.size;
        if (new StatFs(dir.getPath()).getAvailableBytes() < need)
            throw new IOException("The head unit doesn't have enough free space for " + (offers.size() == 1 ? "this update" : "these updates") + ". Install fewer at once.");
        List<String> args = new ArrayList<>();
        for (int i = 0; i < offers.size(); i++) {
            final Offer o = offers.get(i); final int index = i;
            File file = new File(dir, o.id + ".download");
            Progress shown = new Progress() { @Override public void at(int ignored, int percent) { progress.at(index, percent); } };
            progress.at(i, -1);
            fetch(c, current, o.url, o.id.equals(SelfUpdate.TILEPAD) ? 32 << 20 : (int) SelfUpdate.MAX_PACKAGE_BYTES, file, shown);
            if (o.size > 0 && file.length() != o.size) throw new IOException("The download of " + o.name + " was incomplete. Check again to retry.");
            if (!com.tilepad.tweakcore.Files.hash(file).equalsIgnoreCase(o.digest))
                throw new IOException("The download of " + o.name + " doesn't match GitHub's checksum. Check again to retry.");
            String check = o.digest;
            if (o.id.equals(SelfUpdate.TILEPAD)) {
                File sig = new File(dir, SelfUpdate.SIGNATURE);
                fetch(c, current, o.signature, 4096, sig, null);
                sig.setReadable(true, false);
                check = sig.getPath();
            }
            file.setReadable(true, false);
            args.addAll(Arrays.asList(o.id, file.getPath(), check, o.inner == null ? "-" : o.inner));
        }
        return args;
    }
    static void clear(Context c) {
        File dir = new File(c.getFilesDir(), "update");
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }

    /** Release notes as plain text: GitHub's Markdown without its markup. */
    static String notes(String body) {
        StringBuilder out = new StringBuilder(); boolean blank = false;
        for (String raw : body.replace("\r", "").split("\n")) {
            String line = raw.trim()
                .replaceAll("!?\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                .replaceAll("\\*\\*|__|`", "")
                .replaceAll("^#{1,6}\\s+", "")
                .replaceAll("^[-*+]\\s+", "• ");
            if (line.isEmpty()) { blank = out.length() > 0; continue; }
            if (blank) out.append('\n');
            if (out.length() > 0) out.append('\n');
            out.append(line); blank = false;
            if (out.length() > 20000) break;
        }
        return out.toString();
    }

    private static JSONObject json(Context c, String current, String path) throws Exception {
        HttpsURLConnection h = open(c, current, API + path, "application/vnd.github+json", null);
        try { return new JSONObject(new String(body(h, MAX_JSON, null, null), "UTF-8")); } finally { h.disconnect(); }
    }
    private static JSONArray array(Context c, String current, String path) throws Exception {
        HttpsURLConnection h = open(c, current, API + path, "application/vnd.github+json", null);
        try { return new JSONArray(new String(body(h, MAX_JSON, null, null), "UTF-8")); } finally { h.disconnect(); }
    }
    private static void fetch(Context c, String current, String url, int limit, File to, Progress progress) throws Exception {
        HttpsURLConnection h = open(c, current, url, "application/octet-stream", null);
        try { body(h, limit, to, progress); } finally { h.disconnect(); }
    }

    /**
     * An HTTPS request answered 200 (206 for a range), following HTTPS redirects: GitHub sends
     * downloads to another host. 404 is an {@link Answer} for the caller to put in words.
     */
    private static HttpsURLConnection open(Context c, String current, String url, String accept, String range) throws Exception {
        for (int hops = 0; hops < 5; hops++) {
            if (url == null || !url.startsWith("https://")) throw new IOException("GitHub sent an address that isn't HTTPS.");
            NetworkProblem problem = networkProblem(c);
            if (problem != null) throw problem;
            HttpsURLConnection h = (HttpsURLConnection) new URL(url).openConnection();
            h.setSSLSocketFactory(socketFactory(c));
            h.setInstanceFollowRedirects(false);
            h.setConnectTimeout(CONNECT_MS); h.setReadTimeout(READ_MS);
            h.setRequestProperty("User-Agent", "Tilepad/" + current);
            h.setRequestProperty("Accept", accept);
            if (range != null) h.setRequestProperty("Range", range);
            boolean keep = false;
            try {
                int code = h.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    url = h.getHeaderField("Location"); continue;
                }
                if (code == 403 || code == 429) throw new IOException("GitHub is limiting requests from this network right now. Try again in an hour.");
                if (code == 404) throw new Answer("GitHub has nothing at " + url + ".");
                if (code != (range == null ? 200 : 206)) throw new IOException("GitHub answered " + code + ". Try again later.");
                keep = true;
                return h;
            } finally { if (!keep) h.disconnect(); }
        }
        throw new IOException("GitHub redirected too many times.");
    }
    /** The response, into {@code to} when given. */
    private static byte[] body(HttpsURLConnection h, long limit, File to, Progress progress) throws IOException {
        long length = h.getContentLength();
        if (length > limit) throw new IOException("The download is larger than a NEMESIS package should be.");
        ByteArrayOutputStream memory = to == null ? new ByteArrayOutputStream() : null;
        try (InputStream in = h.getInputStream(); OutputStream out = to == null ? memory : new FileOutputStream(to)) {
            byte[] b = new byte[65536]; long total = 0; int shown = -1;
            for (int n; (n = in.read(b)) != -1;) {
                if ((total += n) > limit) throw new IOException("The download is larger than a NEMESIS package should be.");
                out.write(b, 0, n);
                int percent = length > 0 ? (int) (total * 100 / length) : -1;
                if (progress != null && percent != shown) progress.at(0, shown = percent);
            }
        }
        return memory == null ? null : memory.toByteArray();
    }

    /**
     * A release asset read in ranges. GitHub answers each request with a redirect to a signed download
     * address; the first one is reused for the rest of the asset while it lasts.
     */
    private static final class Remote implements ZipProbe.Source {
        private final Context c; private final String current; private String url; private final long size;
        Remote(Context c, String current, String url, long size) { this.c = c; this.current = current; this.url = url; this.size = size; }
        @Override public long size() { return size; }
        @Override public byte[] read(long offset, int length) throws IOException {
            HttpsURLConnection h = request(offset, length);
            try {
                byte[] b = body(h, length, null, null);
                if (b.length != length) throw new IOException("GitHub sent less of a file than asked.");
                return b;
            } finally { h.disconnect(); }
        }
        @Override public InputStream open(long offset, long length) throws IOException {
            final HttpsURLConnection h = request(offset, length);
            return new FilterInputStream(h.getInputStream()) {
                @Override public void close() throws IOException { try { super.close(); } finally { h.disconnect(); } }
            };
        }
        private HttpsURLConnection request(long offset, long length) throws IOException {
            try {
                HttpsURLConnection h = Updater.open(c, current, url, "application/octet-stream", "bytes=" + offset + "-" + (offset + length - 1));
                url = h.getURL().toString();
                return h;
            } catch (IOException e) { throw e; } catch (Exception e) { throw new IOException(e); }
        }
    }

    private static synchronized SSLSocketFactory socketFactory(Context c) throws Exception {
        if (factory != null) return factory;
        SSLContext tls = SSLContext.getInstance("TLSv1.2");
        tls.init(null, new TrustManager[]{new Roots(c)}, null);
        return factory = new Tls12(tls.getSocketFactory());
    }
    private static X509TrustManager trust(KeyStore roots) throws Exception {
        TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        f.init(roots);
        for (TrustManager m : f.getTrustManagers()) if (m instanceof X509TrustManager) return (X509TrustManager) m;
        throw new IllegalStateException("No X509TrustManager");
    }

    /**
     * The system's roots, then the ones in res/raw/update_roots.pem: GitHub's roots (USERTrust ECC,
     * ISRG Root X1) aren't in the head unit's own store, and NEMESIS only adds them on some units.
     */
    private static final class Roots implements X509TrustManager {
        private final X509TrustManager system, bundled;
        Roots(Context c) throws Exception {
            system = trust(null);
            KeyStore roots = KeyStore.getInstance(KeyStore.getDefaultType()); roots.load(null, null);
            CertificateFactory x509 = CertificateFactory.getInstance("X.509");
            ByteArrayOutputStream pem = new ByteArrayOutputStream();
            try (InputStream in = c.getResources().openRawResource(R.raw.update_roots)) {
                byte[] b = new byte[8192]; for (int n; (n = in.read(b)) != -1;) pem.write(b, 0, n);
            }
            int n = 0;
            for (String part : pem.toString("US-ASCII").split("-----END CERTIFICATE-----")) {
                int at = part.indexOf("-----BEGIN CERTIFICATE-----");
                if (at < 0) continue;
                roots.setCertificateEntry("root" + n++, x509.generateCertificate(
                    new ByteArrayInputStream((part.substring(at) + "-----END CERTIFICATE-----\n").getBytes("US-ASCII"))));
            }
            bundled = trust(roots);
        }
        @Override public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException { system.checkClientTrusted(chain, auth); }
        @Override public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
            try { system.checkServerTrusted(chain, auth); } catch (CertificateException e) { bundled.checkServerTrusted(chain, auth); }
        }
        @Override public X509Certificate[] getAcceptedIssuers() { return system.getAcceptedIssuers(); }
    }

    /**
     * GitHub wants TLS 1.2 with AES-GCM. KitKat supports TLS 1.2 but leaves it off by default. Stock
     * 4.4.2 has no GCM suites at all; Hyundai's build adds them as defaults. Any supported but left
     * off are switched on too.
     */
    private static final class Tls12 extends SSLSocketFactory {
        private final SSLSocketFactory d;
        Tls12(SSLSocketFactory d) { this.d = d; }
        private Socket on(Socket s) {
            if (!(s instanceof SSLSocket)) return s;
            SSLSocket tls = (SSLSocket) s;
            tls.setEnabledProtocols(new String[]{"TLSv1.2"});
            java.util.LinkedHashSet<String> suites = new java.util.LinkedHashSet<>();
            for (String suite : tls.getSupportedCipherSuites()) if (suite.startsWith("TLS_ECDHE_") && suite.contains("_GCM_")) suites.add(suite);
            suites.addAll(java.util.Arrays.asList(tls.getEnabledCipherSuites()));
            tls.setEnabledCipherSuites(suites.toArray(new String[0]));
            return s;
        }
        @Override public String[] getDefaultCipherSuites() { return d.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return d.getSupportedCipherSuites(); }
        @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException { return on(d.createSocket(s, host, port, autoClose)); }
        @Override public Socket createSocket(String host, int port) throws IOException { return on(d.createSocket(host, port)); }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException { return on(d.createSocket(host, port, local, localPort)); }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException { return on(d.createSocket(host, port)); }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException { return on(d.createSocket(host, port, local, localPort)); }
    }
    private Updater() { }
}
