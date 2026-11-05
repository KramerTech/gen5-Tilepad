package com.tilepad;

/** Uses the same glyph, status pill and text rows as Wi-Fi, ADB and Precondition. */
final class TweaksTile extends StatusTile {
    TweaksTile(Ui ui, TileSpec spec, EditListener edits) {
        super(ui, spec, edits, Glyph.Kind.TWEAKS, "Tweaks");
        bind(TweaksSnapshot.checking());
    }
    void bind(TweaksSnapshot s) {
        boolean checking = s.enabled < 0 && s.problem == null;
        int color = s.problem != null ? Ui.AMBER : s.on() ? Ui.ACCENT : Ui.MUTED;
        glyph.setColor(color); spinner.setColor(color); spinner.setSpinning(checking);
        setPill(checking ? null : s.problem != null ? "Error" : s.on() ? "On" : "Off", s.on() ? Ui.GREEN : color);
        // The pill says on or off; the headline and detail give the counts.
        show(headline, checking ? "Checking…" : s.problem != null ? "Unavailable"
            : s.enabled == 0 ? "None enabled" : s.enabled == s.total ? "All enabled" : s.enabled + " enabled");
        headline.setTextColor(s.on() ? Ui.ACCENT : Ui.TEXT);
        show(detail, s.enabled < 0 ? "" : s.enabled == 0 ? s.total + " available"
            : s.enabled == s.total ? s.total + " tweaks" : (s.total - s.enabled) + " off");
        show(meta, "Tap to manage");
        setContentDescription("Tweaks, " + (s.enabled >= 0 ? (s.on() ? "on, " : "off, ") + s.enabled + " of " + s.total + " enabled" : checking ? "checking" : s.problem));
    }
}
