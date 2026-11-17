package com.tilepad;

/** Cabin ambient lighting status card, backed by AmbientSnapshot. */
final class AmbientTile extends StatusTile {
    AmbientTile(Ui ui, TileSpec spec, EditListener edits) {
        super(ui, spec, edits, Glyph.Kind.AMBIENT, "Ambient Lighting");
        bind(AmbientSnapshot.checking());
    }

    void bind(AmbientSnapshot s) {
        boolean checking = s.state == AmbientSnapshot.State.CHECKING;
        int accent = s.state == AmbientSnapshot.State.ACTIVE ? (0xff000000 | s.rgb)
            : (s.state == AmbientSnapshot.State.PAUSED || s.state == AmbientSnapshot.State.UNAVAILABLE) ? Ui.AMBER : Ui.MUTED;
        glyph.setColor(accent);
        spinner.setColor(accent);
        spinner.setSpinning(checking);
        setPill(s.pill(), Ui.tone(s.tone()));
        show(headline, s.tileHeadline());
        headline.setTextColor(s.state == AmbientSnapshot.State.ACTIVE ? (0xff000000 | s.rgb)
            : (s.state == AmbientSnapshot.State.PAUSED || s.state == AmbientSnapshot.State.UNAVAILABLE) ? Ui.AMBER : Ui.TEXT);
        show(detail, s.tileDetail());
        show(meta, "Tap for controls");
        setContentDescription("Ambient Lighting, " + s.tileHeadline() + (s.tileDetail().isEmpty() ? "" : ", " + s.tileDetail()));
    }
}
