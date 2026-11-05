package com.tilepad.tweaks;

import java.io.*;
import java.util.Properties;

/** The APK has no privileged exported components and owns no background service. */
final class Backend {
    static final String ENTRY = "/data/nemesis/apps/tilepad/bin/tweaks.sh";
    static Properties run(String... args) throws Exception {
        if (!new File("/system/xbin/nemesis").isFile()) throw new IOException("NEMESIS and the complete Tilepad bundle are required. No settings were changed.");
        StringBuilder command = new StringBuilder("sh ").append(quote(ENTRY));
        for (String arg : args) command.append(' ').append(quote(arg));
        Process p = new ProcessBuilder("/system/xbin/nemesis", "root", "-c", command.toString()).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] b = new byte[4096]; for (int n; (n = in.read(b)) != -1;) if (out.size() < 65536) out.write(b, 0, n);
        }
        int code = p.waitFor(); String text = out.toString("UTF-8");
        if (code != 0) throw new IOException(text.trim().isEmpty() ? "The tweak command failed (" + code + ")." : text.trim());
        Properties result = new Properties();
        if ((args.length == 1 && (args[0].equals("status") || args[0].equals("settings")))
            || (args[0].equals("options") || args[0].equals("update"))) result.load(new StringReader(text));
        return result;
    }
    private static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
    private Backend() { }
}
