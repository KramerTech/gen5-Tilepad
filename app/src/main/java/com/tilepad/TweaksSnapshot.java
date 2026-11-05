package com.tilepad;

import java.io.StringReader;
import java.util.Properties;

/** Small saved-switch summary; a failed read is never presented as "all off". */
final class TweaksSnapshot {
    final int enabled, total;
    final String problem;
    /** The nav handle was hidden when this boot started (Hide Right-Hand Nav Handle). */
    final boolean handleHidden;
    private TweaksSnapshot(int enabled, int total, String problem) { this(enabled, total, problem, false); }
    private TweaksSnapshot(int enabled, int total, String problem, boolean handleHidden) {
        this.enabled = enabled; this.total = total; this.problem = problem; this.handleHidden = handleHidden;
    }
    static TweaksSnapshot checking() { return new TweaksSnapshot(-1, 0, null); }
    static TweaksSnapshot unavailable(String why) { return new TweaksSnapshot(-1, 0, why); }
    static TweaksSnapshot of(int enabled, int total) {
        if (total < 1 || enabled < 0 || enabled > total) return unavailable("Invalid tweak summary");
        return new TweaksSnapshot(enabled, total, null);
    }
    static TweaksSnapshot parse(String text) {
        try {
            Properties p = new Properties(); p.load(new StringReader(text));
            TweaksSnapshot s = of(Integer.parseInt(p.getProperty("enabled")), Integer.parseInt(p.getProperty("total")));
            return s.problem != null ? s : new TweaksSnapshot(s.enabled, s.total, null, "true".equals(p.getProperty("handleHidden")));
        } catch (Exception e) { return unavailable("Could not read tweak settings"); }
    }
    boolean on() { return enabled > 0; }
}
