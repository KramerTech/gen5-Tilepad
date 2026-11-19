package com.tilepad.tweakcore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * The installed NEMESIS packages that describe a Tilepad page of their own (AddonPage), and the
 * commands Tilepad runs for them.
 *
 * /data/nemesis/apps can be written by any app, so a package's folder is trusted only once the root
 * shell is inside it: there the folder, bin, bin/ctl.sh and the page file must each be root's and
 * writable by no one else, or nothing runs. Being inside it first ties that check to the folder the
 * command then uses. The page file is read the same way. Names and values sent are the page's own
 * and only of characters that are safe in the shell.
 * Tests override the property read, the root shell and the folder listing.
 */
public class AddonCtl {
    public static final String APPS = PackageFolder.APPS;
    /** The reply when a package's files aren't root's alone: nothing was run. */
    public static final String UNTRUSTED = PackageFolder.UNTRUSTED;
    /** Tilepad's own folder: a page file there is ignored. */
    public static final List<String> BUILT_IN = java.util.Collections.singletonList("tilepad");
    private static final String NEMESIS = "/system/xbin/nemesis";
    /** A root command still running after this long is stopped. */
    static final long ROOT_TIMEOUT_MS = 30000;
    static final int TIMED_OUT = 124;
    private static Method propertyGet;

    /** A root command's exit status and output. */
    public static final class Reply {
        public final int code;
        public final String output;
        public Reply(int code, String output) { this.code = code; this.output = output == null ? "" : output; }
        public boolean ok() { return code == 0; }
        /** The reason in its error= line, or "" without one. */
        public String error() {
            for (String line : output.split("\n")) if (line.startsWith("error=")) return line.substring(6).trim();
            return "";
        }
    }

    /** A package's page as loaded: the page, or why there isn't one. */
    public static final class Loaded {
        public final String id;
        public final AddonPage page;
        public final String problem;
        Loaded(String id, AddonPage page, String problem) { this.id = id; this.page = page; this.problem = problem; }
    }

    /** The folders under APPS that have a page file and a bin/ctl.sh, sorted (not yet checked as root's). */
    public List<String> candidates() {
        List<String> out = new ArrayList<>();
        String[] names = list(APPS);
        if (names == null) return out;
        Arrays.sort(names);
        for (String id : names) {
            if (!AddonPage.folderName(id)) continue;
            if (exists(APPS + "/" + id + "/" + AddonPage.FILE) && exists(APPS + "/" + id + "/bin/ctl.sh")) out.add(id);
        }
        return out;
    }

    /** Every candidate's page, loaded as root after the check; skip lists folders Tilepad leaves alone (its own). */
    public List<Loaded> load(List<String> skip) {
        List<Loaded> out = new ArrayList<>();
        for (String id : candidates()) if (skip == null || !skip.contains(id)) out.add(load(id));
        return out;
    }

    /** Package id's page, read as root once its folder passes the check. */
    public Loaded load(String id) {
        if (!AddonPage.folderName(id)) return new Loaded(id, null, "its folder name isn't one Tilepad takes");
        Reply r = root(inFolder(id, new String[]{AddonPage.FILE}, "cat ./" + AddonPage.FILE));
        if (UNTRUSTED.equals(r.error())) return new Loaded(id, null, "its files aren't the system's alone, so Tilepad doesn't use them");
        if (!r.ok()) return new Loaded(id, null, "its page file couldn't be read");
        try {
            return new Loaded(id, AddonPage.parse(id, r.output), null);
        } catch (AddonPage.Invalid e) {
            return new Loaded(id, null, e.getMessage());
        }
    }

    /** Sets page's setting prop to value through its package's ctl.sh, if the page has that setting and allows that value. */
    public Reply set(AddonPage page, String prop, String value) {
        AddonPage.Control c = page.setting(prop);
        if (c == null) return new Reply(2, "error=unknown_name");
        if (!page.allows(c, value)) return new Reply(2, "error=bad_value");
        return root(inFolder(page.id, new String[0], "sh ./bin/ctl.sh set " + quote(c.key) + " " + quote(value)));
    }

    /** Sends page's request verb (one of its actions) with its package's ctl.sh req: the seq it answers, or -1 when it wasn't sent or didn't answer one. */
    public long req(AddonPage page, String verb) {
        if (page.results == null || page.action(verb) == null) return -1;
        Reply r = root(inFolder(page.id, new String[0], "sh ./bin/ctl.sh req " + quote(verb)));
        if (!r.ok()) return -1;
        for (String line : r.output.split("\n")) {
            String t = line.trim();
            if (t.startsWith("seq=") && t.substring(4).matches("[0-9]{1,18}")) return Long.parseLong(t.substring(4));
        }
        return -1;
    }

    /**
     * Request verb:seq's result word in res (what the page's results property holds, "<verb>:<seq>:<word>"); null
     * while it's still to come; "" once a later request's result is there.
     */
    public static String result(String res, String verb, long seq) {
        if (res == null) return null;
        String[] f = res.split(":", 3);
        if (f.length != 3 || !f[1].matches("[0-9]{1,18}")) return null;
        long got = Long.parseLong(f[1]);
        if (got < seq) return null;
        return got == seq && f[0].equals(verb) ? f[2] : "";
    }

    /** What to tell the driver after action c's request (seq: -1 when it wasn't sent; word: its answer, null for none). */
    public static String said(AddonPage.Control c, long seq, String word) {
        if (seq < 0) return "The package's command didn't run, so nothing was done.";
        if (word == null) return "The package didn't answer in time. It may still finish; check the page again in a moment.";
        if (word.isEmpty()) return "Another request came after this one, so its answer isn't known.";
        String own = c.results.get(word);
        return own != null ? own : "The package answered: " + word.replace('_', ' ') + ".";
    }

    /** What to tell the driver when a setting didn't take (reply: set's); null when it did. */
    public static String setProblem(Reply reply) {
        if (reply.ok()) return null;
        switch (reply.error()) {
            case UNTRUSTED: return "This package's files aren't the system's alone, so Tilepad didn't run its settings command.";
            case "bad_value": return "The package didn't accept that value, so the setting is as it was.";
            case "unknown_name": return "The installed package doesn't have that setting, so nothing changed.";
            case "setprop_failed": return "The head unit didn't save the setting, so it's as it was.";
            default: return "The package's settings command didn't finish, so the change may not have taken.";
        }
    }

    /** Every property in names that's set, read without root. */
    public Properties read(Iterable<String> names) {
        Properties out = new Properties();
        for (String name : names) {
            String v = prop(name);
            if (!v.isEmpty()) out.setProperty(name, v);
        }
        return out;
    }

    /** PackageFolder.run in package id's folder, checking bin, bin/ctl.sh and files too. */
    static String inFolder(String id, String[] files, String then) {
        String[] all = new String[files.length + 2];
        all[0] = "bin"; all[1] = "bin/ctl.sh";
        System.arraycopy(files, 0, all, 2, files.length);
        return PackageFolder.run(APPS + "/" + id, all, then);
    }

    static String quote(String s) { return PackageFolder.quote(s); }

    /** A system property, "" when it's unset or can't be read. */
    public String prop(String name) {
        try {
            Method get = propertyGet;
            if (get == null) propertyGet = get = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
            Object value = get.invoke(null, name);
            return value == null ? "" : (String) value;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }

    protected String[] list(String dir) { return new File(dir).list(); }

    protected boolean exists(String path) { return new File(path).isFile(); }

    /**
     * Runs command through `nemesis root -c`. Blocking, for up to ROOT_TIMEOUT_MS: a command still
     * running then is stopped (TIMED_OUT), and so is one whose run fails or is interrupted.
     */
    protected Reply root(String command) {
        if (!new File(NEMESIS).isFile()) return new Reply(127, "");
        Process process = null;
        Thread watchdog = null;
        final boolean[] stopped = {false};
        try {
            process = new ProcessBuilder(NEMESIS, "root", "-c", command).redirectErrorStream(true).start();
            final Process running = process;
            watchdog = new Thread(new Runnable() {
                @Override public void run() {
                    try { Thread.sleep(ROOT_TIMEOUT_MS); } catch (InterruptedException finished) { return; }
                    synchronized (stopped) { stopped[0] = true; }
                    running.destroy();
                }
            }, "addon-ctl-timeout");
            watchdog.setDaemon(true);
            watchdog.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                for (int n; (n = in.read(buffer)) > 0; ) if (out.size() < AddonPage.MAX_FILE + 4096) out.write(buffer, 0, n);
            }
            int code = process.waitFor();
            synchronized (stopped) { if (stopped[0]) code = TIMED_OUT; }
            return new Reply(code, out.toString("UTF-8"));
        } catch (InterruptedException e) {
            process.destroy();
            Thread.currentThread().interrupt();
            return new Reply(1, "");
        } catch (Exception e) {
            if (process != null) process.destroy();
            return new Reply(1, "");
        } finally {
            if (watchdog != null) watchdog.interrupt();
        }
    }
}
