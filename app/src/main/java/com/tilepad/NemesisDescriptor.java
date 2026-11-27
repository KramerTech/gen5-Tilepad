package com.tilepad;

import java.util.ArrayList;
import java.util.List;

/** What one NEMESIS.data says about icons: its favicon (descriptor 1.2) and the Android packages it installs. */
final class NemesisDescriptor {
    /** Path inside the package, or null when it names none (or one NEMESIS would refuse). */
    final String favicon;
    final List<String> packages;

    private NemesisDescriptor(String favicon, List<String> packages) {
        this.favicon = favicon;
        this.packages = packages;
    }

    static NemesisDescriptor parse(String text) {
        String section = "";
        String favicon = null, kind = null, pkg = null, component = null;
        List<String> packages = new ArrayList<>();
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                addApp(section, kind, pkg, component, packages);
                section = line.substring(1, line.length() - 1).trim();
                kind = pkg = component = null;
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).trim();
            String value = unquote(line.substring(eq + 1).trim());
            if (section.equals("nemesis") && key.equals("favicon")) favicon = value;
            else if (key.equals("kind")) kind = value;
            else if (key.equals("package")) pkg = value;
            else if (key.equals("component")) component = value;
        }
        addApp(section, kind, pkg, component, packages);
        // NEMESIS refuses the same paths: the favicon must stay inside the package.
        if (favicon != null && (favicon.isEmpty() || favicon.startsWith("/")
            || favicon.contains("..") || favicon.contains("\\"))) favicon = null;
        return new NemesisDescriptor(favicon, packages);
    }

    private static void addApp(String section, String kind, String pkg, String component, List<String> packages) {
        if (!section.startsWith("app.") || (kind != null && !kind.equals("apk"))) return;
        String name = pkg;
        if ((name == null || name.isEmpty()) && component != null && component.indexOf('/') > 0) {
            name = component.substring(0, component.indexOf('/'));
        }
        if (name != null && !name.isEmpty() && !packages.contains(name)) packages.add(name);
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
