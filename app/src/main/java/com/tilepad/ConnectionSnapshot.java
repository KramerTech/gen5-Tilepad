package com.tilepad;

import java.util.Locale;

/** Read-only display model for the Wi-Fi tile. */
final class ConnectionSnapshot {
    /** TURNING_ON and CONNECTING: on its way to connected, so the tile says to wait rather than tap. */
    enum State { CONNECTED, CONNECTING, TURNING_ON, DISCONNECTED, OFF, UNKNOWN }
    final State state;
    final String network;
    final String ip;
    /** Signal bars 0–3, or -1 when unknown or not connected. */
    final int signal;
    /** Link speed in Mbps, or 0 when unknown. */
    final int linkMbps;

    private ConnectionSnapshot(State state, String network, String ip) {
        this(state, network, ip, -1, 0);
    }
    private ConnectionSnapshot(State state, String network, String ip, int signal, int linkMbps) {
        this.state = state;
        this.network = network;
        this.ip = ip;
        this.signal = signal;
        this.linkMbps = linkMbps;
    }

    /** Adds link quality to a connected snapshot; other states have none. */
    ConnectionSnapshot withLink(int signal, int linkMbps) {
        if (state != State.CONNECTED) return this;
        return new ConnectionSnapshot(state, network, ip,
            Math.max(-1, Math.min(3, signal)), Math.max(0, linkMbps));
    }

    static ConnectionSnapshot unknown() {
        return new ConnectionSnapshot(State.UNKNOWN, "Unavailable", "—");
    }

    static ConnectionSnapshot read(boolean enabled, boolean associated, String ssid, int address) {
        if (!enabled) return new ConnectionSnapshot(State.OFF, "Wi-Fi is off", "—");
        if (!associated || address == 0) {
            return new ConnectionSnapshot(State.DISCONNECTED, "Not connected", "—");
        }
        String network = network(ssid);
        return new ConnectionSnapshot(State.CONNECTED, network == null ? "Unknown network" : network, formatIp(address));
    }

    /**
     * Shows off or disconnected as on the way: enabling (the radio is starting), joining (the
     * supplicant is associating or DHCP hasn't finished, joining ssid if it's known) or expected
     * (something Tilepad started, like the ADB tile, is turning Wi-Fi on).
     */
    ConnectionSnapshot onTheWay(boolean enabling, boolean joining, boolean expected, String ssid) {
        if (state == State.OFF && (enabling || expected)) return new ConnectionSnapshot(State.TURNING_ON, "Turning on…", "—");
        if (state == State.DISCONNECTED && (joining || expected)) {
            String network = joining ? network(ssid) : null;
            return new ConnectionSnapshot(State.CONNECTING, network == null ? "Connecting…" : network, "—");
        }
        return this;
    }

    /** The SSID without its quotes, or null when there isn't a real one. */
    private static String network(String ssid) {
        String network = ssid == null ? "" : ssid.trim();
        if (network.length() >= 2 && network.startsWith("\"") && network.endsWith("\"")) {
            network = network.substring(1, network.length() - 1);
        }
        return network.isEmpty() || network.startsWith("<") ? null : network;
    }

    static String formatIp(int address) {
        return String.format(Locale.US, "%d.%d.%d.%d", address & 255,
            address >>> 8 & 255, address >>> 16 & 255, address >>> 24 & 255);
    }
}
