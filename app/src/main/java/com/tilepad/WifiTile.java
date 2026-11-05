package com.tilepad;

/**
 * Connection state, network name and address; tapping opens Android's network picker.
 * On a hotspot-only unit it offers to turn on the Wi-Fi client instead.
 */
final class WifiTile extends StatusTile {
    WifiTile(Ui ui, TileSpec spec, EditListener edits) {
        super(ui, spec, edits, Glyph.Kind.WIFI, "Wi-Fi Client");
        bind(ConnectionSnapshot.unknown());
    }

    void bind(ConnectionSnapshot c) {
        switch (c.state) {
            case CONNECTED: {
                glyph.setKind(Glyph.Kind.WIFI);
                glyph.setColor(Ui.ACCENT);
                glyph.setSignal(c.signal < 0 ? 3 : c.signal);
                setPill("Connected", Ui.GREEN);
                show(headline, c.network);
                show(detail, c.ip);
                show(meta, c.linkMbps > 0 ? c.linkMbps + " Mbps" : "");
                setContentDescription("Wi-Fi connected to " + c.network + ", " + c.ip);
                break;
            }
            case DISCONNECTED:
                glyph.setKind(Glyph.Kind.WIFI);
                glyph.setColor(Ui.MUTED); glyph.setSignal(0);
                setPill("On", Ui.AMBER);
                show(headline, "No network");
                show(detail, "");
                show(meta, "Tap to choose a network");
                setContentDescription("Wi-Fi not connected");
                break;
            case OFF:
                glyph.setKind(Glyph.Kind.WIFI_OFF);
                glyph.setColor(Ui.MUTED); glyph.setSignal(0);
                setPill("Off", Ui.MUTED);
                show(headline, "No network");
                show(detail, "");
                show(meta, "Tap to open settings");
                setContentDescription("Wi-Fi off");
                break;
            default:
                glyph.setKind(Glyph.Kind.WIFI);
                glyph.setColor(Ui.MUTED); glyph.setSignal(0);
                setPill(null, Ui.MUTED);
                show(headline, "Checking…");
                show(detail, "");
                show(meta, "");
                setContentDescription("Wi-Fi");
        }
    }

    /** The unit can't join networks yet; pendingReboot once the client is on but not started. */
    void bindClientOff(boolean pendingReboot) {
        glyph.setKind(Glyph.Kind.WIFI_OFF);
        glyph.setColor(Ui.MUTED); glyph.setSignal(0);
        setPill(pendingReboot ? "Pending" : "Client off", pendingReboot ? Ui.AMBER : Ui.MUTED);
        show(headline, pendingReboot ? "Reboot to finish" : "Hotspot only");
        show(detail, "");
        show(meta, pendingReboot ? "Tap to reboot" : "Tap to turn on Wi-Fi");
        setContentDescription(pendingReboot ? "Wi-Fi client on, reboot to finish" : "Wi-Fi client off");
    }
}
