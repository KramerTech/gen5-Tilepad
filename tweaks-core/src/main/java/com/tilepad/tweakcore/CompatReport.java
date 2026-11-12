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

    /** A support log's name: one per capture, so a second one doesn't replace the first. */
    public static String supportFileName(String product, Date when) {
        String safe = product == null ? "" : product.replaceAll("[^A-Za-z0-9._-]", "_");
        return "tilepad-log-" + (safe.isEmpty() ? "unknown" : safe) + "-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(when) + ".zip";
    }

    /** Processes whose logs, memory maps and windows a support log records: the panels Tilepad patches. */
    static final String[] PANELS = {"com.mobis.splitscreen.right", "com.android.systemui"};

    /** From `dumpsys window windows`: each window's owner, type, flags, frame and visibility. */
    public static String windowLines(String dump) { return keep(dump, WINDOW); }
    private static final Pattern WINDOW = Pattern.compile(
        "\\s*(Window #|mOwnerUid=|mAttrs=|mHasSurface=|mFrame=|mViewVisibility=|mCurrentFocus=|mFocusedApp=|isReadyForDisplay).*");
    /** From `dumpsys activity activities`: the task stacks' activities and which one is resumed. */
    public static String activityLines(String dump) { return keep(dump, ACTIVITY); }
    private static final Pattern ACTIVITY = Pattern.compile("\\s*(\\* Hist #|Run #|mResumedActivity|mFocusedActivity|Stack #|Task id #).*");
    private static String keep(String dump, Pattern lines) {
        StringBuilder out = new StringBuilder();
        for (String line : dump.split("\r?\n")) if (lines.matcher(line).matches()) out.append(line).append('\n');
        return out.toString();
    }

    /**
     * The lines of `logcat -v threadtime` a support log keeps, at most the last {@code max}: Tilepad's
     * own, activity starts and process deaths, crashes, the consent screen, and the panel
     * processes' (pids) lines about OSDs, views and modes. Media, call, radio and contact lines are
     * dropped, and long digit runs (phone numbers) masked.
     */
    public static String supportLog(String logcat, Set<String> pids, int max) {
        Deque<String> kept = new ArrayDeque<>();
        for (String line : logcat.split("\r?\n")) {
            Matcher m = LOG_LINE.matcher(line);
            if (!m.matches()) continue;
            String pid = m.group(1), tag = m.group(2).trim(), message = m.group(3), lower = tag.toLowerCase(Locale.US);
            String prefix = line.substring(0, m.start(3));
            boolean keep;
            if (lower.startsWith("tilepad") || lower.startsWith("longstar") || lower.startsWith("tweaks")) keep = true;
            else if (tag.equals("ActivityManager")) {
                keep = AM_EVENT.matcher(message).find();
                message = message.replaceAll(" dat=\\S+", "");
            }
            else if (tag.equals("AndroidRuntime")) keep = true;
            // Driver profiles can carry names the owner chose.
            else if (tag.startsWith("ConsentScreen")) keep = !NAMES.matcher(message).find();
            else if (pids.contains(pid)) keep = PANEL.matcher(message).find() && !PRIVATE.matcher(message).find()
                && !PRIVATE.matcher(tag).find();
            else keep = false;
            if (!keep) continue;
            // Activity lines carry no numbers once intent data is gone; their flags and pids stay readable.
            kept.add(prefix + (tag.equals("ActivityManager") ? message : DIGITS.matcher(message).replaceAll("#")));
            if (kept.size() > max) kept.removeFirst();
        }
        StringBuilder out = new StringBuilder();
        for (String line : kept) out.append(line).append('\n');
        return out.toString();
    }
    /** The pids `Start proc` lines gave the panel processes, so lines from before a restart still count. */
    public static Set<String> startedPids(String logcat, String... processes) {
        Set<String> pids = new HashSet<>();
        Matcher m = START_PROC.matcher(logcat);
        while (m.find()) for (String name : processes) if (m.group(1).equals(name)) pids.add(m.group(2));
        return pids;
    }
    private static final Pattern LOG_LINE = Pattern.compile("\\S+\\s+\\S+\\s+(\\d+)\\s+\\d+\\s+[VDIWEFA]\\s+(.*?): (.*)");
    private static final Pattern AM_EVENT = Pattern.compile("START u0|Displayed |Start proc |has died|ANR in|Force stopping|Killing ");
    private static final Pattern START_PROC = Pattern.compile("Start proc (\\S+) for .*?pid=(\\d+)");
    private static final Pattern PANEL = Pattern.compile("(?i)osd|pop-?over|split|handle|show|cancel|visib|view|mode|panel|window|nav|regulation|consent");
    private static final Pattern PRIVATE = Pattern.compile("(?i)artist|album|title|song|track|number|name|call|phone|contact|address|dest|poi|freq|station|channel|rds|sms|message|text|bt|bluetooth|device|mac|lat|lon|gps|location|account|user|profile");
    private static final Pattern NAMES = Pattern.compile("(?i)name|phone|number|account|mail|nick");
    private static final Pattern DIGITS = Pattern.compile("\\+?\\d[\\d\\- ()]{6,}\\d");

    static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
    private CompatReport() { }
}
