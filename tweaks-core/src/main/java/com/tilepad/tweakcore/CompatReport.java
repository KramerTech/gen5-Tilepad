package com.tilepad.tweakcore;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A compatibility log for a car whose firmware differs from the captured one: the head unit's
 * identity, the SHA-256 of every file a tweak is gated on, and why each tweak is unavailable.
 * {@link UsbReport} zips it with the firmware code those gates cover, for the owner to attach to
 * a GitHub issue. No I/O here.
 */
public final class CompatReport {
    public static final String ISSUES = "https://github.com/KramerTech/gen5-Tilepad/issues/new";
    static final String[] PROPERTIES = {"ro.build.product", "ro.product.device", "ro.product.name", "ro.product.model",
        "ro.build.display.id", "ro.build.fingerprint", "ro.build.version.release", "ro.build.version.sdk", "ro.build.date",
        "ro.product.vcrm.model", "ro.product.vehicleinfo", "ro.product.opt.vehicletype", "ro.product.opt.vehiclesegment",
        "ro.daudio.telematics.features", "persist.sys.climate.variant", "persist.sys.climate.option1", "persist.sys.climate.option2",
        "persist.sys.climate.option3", "persist.sys.climate.option4", "persist.sys.climate.option5"};
    /** Vehicle services whose state the log records (equipment options, climate), numbers only. */
    static final String[] SERVICES = {"com.hkmc.micom.usm", "com.hkmc.micom.bcm", "com.hkmc.micom.datc"};
    /** Vehicle, projection and EV Settings gates (Platform, ProjectionController, ScheduledCharging). */
    static final String[] GATED = {"/system/framework/automotive.odex", "/system/framework/automotive-service.odex",
        "/system/framework/framework.odex", "/system/framework/framework2.odex", "/system/app/HKMC_TAppEV3.apk"};
    private static final Pattern USB = Pattern.compile("(?m)^\\S+\\s+(/mnt/media_rw/usb\\d+)\\s");

    /** The first mounted USB drive's raw mount point (owned by media_rw), or null. */
    public static String usbMount(String procMounts) {
        Matcher m = USB.matcher(procMounts);
        return m.find() ? m.group(1) : null;
    }

    public static String fileName(String product) {
        String safe = product == null ? "" : product.replaceAll("[^A-Za-z0-9._-]", "_");
        return "tilepad-compat-" + (safe.isEmpty() ? "unknown" : safe) + ".zip";
    }

    /**
     * Only the lines of a service dump whose value is a number (signals and equipment options);
     * text values can hold personal data such as digital-key phone names.
     */
    public static String numericLines(String dump) {
        StringBuilder out = new StringBuilder();
        for (String line : dump.split("\r?\n"))
            if (NUMERIC.matcher(line).matches()) out.append(line.trim()).append('\n');
        return out.toString();
    }
    private static final Pattern NUMERIC = Pattern.compile("\\s*[A-Za-z][\\w\\[\\]. ]*\\s*[:=]\\s*(-?\\d+|0x[0-9A-Fa-f]+)\\s*");

    /** Plain text; identity and hashes keep their order, then each tweak's state and reason. */
    public static String text(Map<String, String> identity, Map<String, String> files, Properties status) {
        StringBuilder out = new StringBuilder();
        out.append("Tilepad compatibility log\n");
        out.append("Attach the .zip this came in to a new issue: ").append(ISSUES).append("\n\n[identity]\n");
        for (Map.Entry<String, String> e : identity.entrySet()) out.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        out.append("\n[files] sha256 (stock = Tilepad's backup of a file it has patched)\n");
        for (Map.Entry<String, String> e : files.entrySet()) out.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        out.append("\n[tweaks] enabled, then why it's unavailable (blank = available)\n");
        for (String id : Config.IDS) {
            out.append(id).append('=').append(status.getProperty(id, "false"));
            String reason = status.getProperty("unavailable." + id, "");
            if (!reason.isEmpty()) out.append("  ").append(reason.replace('\n', ' '));
            out.append('\n');
        }
        return out.toString();
    }

    static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
    private CompatReport() { }
}
