package com.tilepad.tweaks;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import com.tilepad.tweakcore.SelfUpdate;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Support's Check for Updates, app side: finds GitHub's latest release and downloads its tilepad.zip
 * and signature, over Wi-Fi only. The root command checks and installs the download (SelfUpdate).
 */
final class Updater {
    static final String LATEST = "https://api.github.com/repos/KramerTech/gen5-Tilepad/releases/latest";
    private static final int CONNECT_MS = 15000, READ_MS = 30000, MAX_JSON = 1 << 20;
    /** The unit reads 2015 until it has the real time, and certificates can't be checked before then. */
    private static final long CLOCK_SET = 1761955200000L; // 2025-11-01
    private static SSLSocketFactory factory;

    static final class Release {
        String version, zip, signature;
        long size;
        /** The changelog's "- " lines. */
        String changes = "";
    }
    interface Progress { void percent(int done); }

    /** Why nothing may be downloaded now, or null. Checked before every request. */
    static String networkProblem(Context c) {
        if (System.currentTimeMillis() < CLOCK_SET)
            return "The head unit's clock isn't set yet, so GitHub's certificate can't be checked. Try again once the clock shows the right time.";
        // The SDK emulator has no Wi-Fi, only its one emulated link.
        if (Build.HARDWARE.contains("goldfish") || Build.HARDWARE.contains("ranchu")) return null;
        WifiManager wifi = (WifiManager) c.getSystemService(Context.WIFI_SERVICE);
        if (wifi == null || !wifi.isWifiEnabled())
            return "Wi-Fi is off. Turn it on and connect to a network (a phone hotspot works), then check again. Tilepad only downloads over Wi-Fi, never the car's built-in modem.";
        NetworkInfo active = ((ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetworkInfo();
        if (active == null || !active.isConnected() || active.getType() != ConnectivityManager.TYPE_WIFI)
            return "Wi-Fi isn't connected to a network. Connect to one (a phone hotspot works), then check again.";
        String route = null;
        try { route = SelfUpdate.defaultRoute(new String(com.tilepad.tweakcore.Files.read(new File("/proc/net/route")), "UTF-8")); } catch (IOException ignored) { }
        if (route == null || !route.startsWith("wlan"))
            return "Wi-Fi is connected, but the head unit is sending internet traffic through the car's modem, so Tilepad won't download. Reconnect Wi-Fi and check again.";
        return null;
    }

    /** GitHub's latest release. Its zip and signature are null when it has none (a release from before 1.2.0). */
    static Release latest(Context c, String current) throws Exception {
        JSONObject o = new JSONObject(new String(get(c, LATEST, current, "application/vnd.github+json", MAX_JSON, null, null), "UTF-8"));
        Release r = new Release();
        r.version = SelfUpdate.version(o.optString("tag_name"));
        if (r.version == null) throw new IOException("GitHub's latest release has no version number.");
        JSONArray assets = o.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            String name = a.optString("name"), url = a.optString("browser_download_url");
            if (name.equals(SelfUpdate.ZIP)) { r.zip = url; r.size = a.optLong("size"); }
            else if (name.equals(SelfUpdate.SIGNATURE)) r.signature = url;
        }
        StringBuilder changes = new StringBuilder();
        for (String line : o.optString("body").split("\r?\n"))
            if (line.startsWith("- ")) changes.append(changes.length() == 0 ? "" : "\n").append("• ").append(line.substring(2).trim());
        r.changes = changes.toString();
        return r;
    }

    /**
     * The release's zip and signature, in a folder of the app's own that root can read (it has no DAC
     * override) and no other app can write. Returns {zip, signature}.
     */
    static File[] download(Context c, Release r, String current, Progress progress) throws Exception {
        File dir = new File(c.getFilesDir(), "update");
        clear(c);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Can't make room for the download.");
        for (File d : new File[]{c.getFilesDir(), dir}) d.setExecutable(true, false);
        File zip = new File(dir, SelfUpdate.ZIP), signature = new File(dir, SelfUpdate.SIGNATURE);
        save(get(c, r.signature, current, "application/octet-stream", 4096, null, null), signature);
        get(c, r.zip, current, "application/octet-stream", 32 << 20, zip, progress);
        if (r.size > 0 && zip.length() != r.size) throw new IOException("The download was incomplete. Check again to retry.");
        for (File f : new File[]{zip, signature}) f.setReadable(true, false);
        return new File[]{zip, signature};
    }
    static void clear(Context c) {
        File dir = new File(c.getFilesDir(), "update");
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }
    private static void save(byte[] bytes, File file) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
    }

    /** HTTPS GET following HTTPS redirects (GitHub sends downloads to another host). Into {@code to} when given. */
    private static byte[] get(Context c, String url, String current, String accept, int limit, File to, Progress progress) throws Exception {
        for (int hops = 0; hops < 5; hops++) {
            if (url == null || !url.startsWith("https://")) throw new IOException("GitHub sent an address that isn't HTTPS.");
            String problem = networkProblem(c);
            if (problem != null) throw new IOException(problem);
            HttpsURLConnection h = (HttpsURLConnection) new URL(url).openConnection();
            h.setSSLSocketFactory(socketFactory(c));
            h.setInstanceFollowRedirects(false);
            h.setConnectTimeout(CONNECT_MS); h.setReadTimeout(READ_MS);
            h.setRequestProperty("User-Agent", "Tilepad/" + current);
            h.setRequestProperty("Accept", accept);
            try {
                int code = h.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    url = h.getHeaderField("Location"); continue;
                }
                if (code == 403 || code == 429) throw new IOException("GitHub is limiting requests from this network right now. Try again later.");
                if (code == 404) throw new IOException("GitHub has no Tilepad release to offer.");
                if (code != 200) throw new IOException("GitHub answered " + code + ". Try again later.");
                long length = h.getContentLength();
                if (length > limit) throw new IOException("The download is larger than a Tilepad release.");
                ByteArrayOutputStream memory = to == null ? new ByteArrayOutputStream() : null;
                try (InputStream in = h.getInputStream(); OutputStream out = to == null ? memory : new FileOutputStream(to)) {
                    byte[] b = new byte[65536]; long total = 0; int shown = -1;
                    for (int n; (n = in.read(b)) != -1;) {
                        if ((total += n) > limit) throw new IOException("The download is larger than a Tilepad release.");
                        out.write(b, 0, n);
                        int percent = length > 0 ? (int) (total * 100 / length) : -1;
                        if (progress != null && percent != shown) progress.percent(shown = percent);
                    }
                }
                return memory == null ? null : memory.toByteArray();
            } finally { h.disconnect(); }
        }
        throw new IOException("GitHub redirected too many times.");
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
