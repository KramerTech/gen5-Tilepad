package com.tilepad;

import android.os.SystemClock;

import java.lang.reflect.InvocationTargetException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Turns the Wi-Fi client on for the ADB tile: ADB over Wi-Fi needs the head unit on the same
 * network as the computer. The radio runs either the client or the hotspot wireless
 * CarPlay/Android Auto use, and the head unit boots with the hotspot up. The hotspot has to go
 * first (this firmware's WifiController ignores a client toggle while it's on).
 *
 * Talks to the framework's IWifiManager directly, through reflection (hidden API on 4.4), so the
 * same code runs in Tilepad (which holds CHANGE_WIFI_STATE) and at boot as root under
 * app_process, where there is no Context ({@link #main}, started by svc/longstar.sh).
 */
public final class WifiRadio {
    // WifiManager.WIFI_STATE_* and WIFI_AP_STATE_* (the latter hidden on 4.4)
    private static final int CLIENT_ENABLING = 2, CLIENT_ENABLED = 3;
    private static final int AP_DISABLING = 10, AP_ENABLING = 12, AP_ENABLED = 13;
    private static final long HOTSPOT_OFF_TIMEOUT_MS = 15000;
    /** At boot the Wi-Fi service may not be up yet; keep trying this long. */
    private static final long BOOT_TRY_MS = 120000;
    private static final long BOOT_RETRY_MS = 5000;

    private WifiRadio() { }

    /** Boot, while the ADB tile has ADB on: run as root by svc/longstar.sh under app_process. */
    public static void main(String[] args) {
        long end = SystemClock.uptimeMillis() + BOOT_TRY_MS;
        String problem;
        while ((problem = ensureClientOn()) != null && SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(BOOT_RETRY_MS);
        }
        log(problem == null ? "Wi-Fi is on (ADB is on)" : "gave up turning on Wi-Fi: " + problem);
    }

    /** Blocking; call from a worker thread. Returns null once the client is on or turning on, else why not. */
    static String ensureClientOn() {
        try {
            Object wifi = service();
            if (wifi == null) return "The Wi-Fi service isn't running.";
            int client = (Integer) call(wifi, "getWifiEnabledState");
            if (client == CLIENT_ENABLED || client == CLIENT_ENABLING) return null;
            int ap = (Integer) call(wifi, "getWifiApEnabledState");
            if (ap == AP_ENABLED || ap == AP_ENABLING) {
                iface().getMethod("setWifiApEnabled", Class.forName("android.net.wifi.WifiConfiguration"), boolean.class)
                    .invoke(wifi, null, false);
            }
            long end = SystemClock.uptimeMillis() + HOTSPOT_OFF_TIMEOUT_MS;
            for (ap = (Integer) call(wifi, "getWifiApEnabledState"); ap == AP_ENABLED || ap == AP_ENABLING || ap == AP_DISABLING;
                    ap = (Integer) call(wifi, "getWifiApEnabledState")) {
                if (SystemClock.uptimeMillis() > end) return "The CarPlay hotspot didn't turn off, so Wi-Fi couldn't turn on.";
                SystemClock.sleep(250);
            }
            boolean ok = (Boolean) iface().getMethod("setWifiEnabled", boolean.class).invoke(wifi, true);
            return ok ? null : "Couldn't turn on Wi-Fi.";
        } catch (InvocationTargetException e) {
            return "Couldn't turn on Wi-Fi: " + e.getCause();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "Couldn't turn on Wi-Fi: " + e;
        }
    }

    private static Object service() throws ReflectiveOperationException {
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "wifi");
        if (binder == null) return null;
        return Class.forName("android.net.wifi.IWifiManager$Stub")
            .getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
    }

    /** Methods come from the public interface: the binder proxy class itself is private. */
    private static Class<?> iface() throws ClassNotFoundException {
        return Class.forName("android.net.wifi.IWifiManager");
    }

    private static Object call(Object target, String method) throws ReflectiveOperationException {
        return iface().getMethod(method).invoke(target);
    }

    private static void log(String msg) {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        System.out.println(ts + " wifi " + msg);
        System.out.flush();
    }
}
