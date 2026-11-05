package com.tilepad;

/**
 * ADB-over-Wi-Fi toggle. "On" means adbd listens over TCP (any computer on the network can
 * connect); "off" means it doesn't. MainActivity reads the state from adbd's properties.
 */
final class AdbTile extends StatusTile {
    AdbTile(Ui ui, TileSpec spec, EditListener edits) {
        super(ui, spec, edits, Glyph.Kind.BUG, "ADB over TCP");
        bind(AdbSnapshot.checking());
    }

    void bind(AdbSnapshot s) {
        switch (s.state) {
            case ON: {
                glyph.setColor(Ui.ACCENT);
                spinner.setColor(Ui.ACCENT);
                spinner.setSpinning(false);
                setPill("On", Ui.GREEN);
                show(headline, "On");
                headline.setTextColor(Ui.ACCENT);
                show(detail, "Port " + s.port);
                show(meta, "Tap to turn off");
                setContentDescription("ADB on, any computer on the network can connect on port " + s.port);
                break;
            }
            case STARTING:
                busy("Starting", "Opening ADB", Ui.AMBER);
                break;
            case STOPPING:
                busy("Stopping", "Closing ADB", Ui.MUTED);
                break;
            case ERROR:
                glyph.setColor(Ui.RED);
                spinner.setColor(Ui.RED);
                spinner.setSpinning(false);
                setPill("Error", Ui.RED);
                show(headline, "Error");
                headline.setTextColor(Ui.TEXT);
                show(detail, "");
                show(meta, "Tap for details");
                setContentDescription("ADB error. " + s.detail);
                break;
            case CHECKING:
                glyph.setColor(Ui.MUTED);
                spinner.setColor(Ui.MUTED);
                spinner.setSpinning(true);
                setPill(null, Ui.MUTED);
                show(headline, "Checking…");
                headline.setTextColor(Ui.TEXT);
                show(detail, "");
                show(meta, "");
                setContentDescription("ADB, checking");
                break;
            default: // OFF
                glyph.setColor(Ui.MUTED);
                spinner.setColor(Ui.MUTED);
                spinner.setSpinning(false);
                setPill("Off", Ui.MUTED);
                show(headline, "Off");
                headline.setTextColor(Ui.TEXT);
                show(detail, "");
                show(meta, "Tap to turn on");
                setContentDescription("ADB off");
        }
    }

    private void busy(String pill, String headlineText, int color) {
        glyph.setColor(color);
        spinner.setColor(color);
        spinner.setSpinning(true);
        setPill(pill, color);
        show(headline, headlineText);
        headline.setTextColor(Ui.TEXT);
        show(detail, "");
        show(meta, "");
        setContentDescription("ADB " + headlineText);
    }
}
