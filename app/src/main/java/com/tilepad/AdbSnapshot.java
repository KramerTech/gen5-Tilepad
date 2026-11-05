package com.tilepad;

/**
 * Read-only display model for the ADB tile: whether adbd is listening over TCP, read from the
 * same properties adbd itself uses. On this head unit adbd doesn't check keys over TCP, so on
 * means any computer on the network can connect.
 */
final class AdbSnapshot {
    enum State { CHECKING, OFF, STARTING, ON, STOPPING, ERROR }

    final State state;
    /** adbd's TCP port while on, else 0. */
    final int port;
    final String detail;

    private AdbSnapshot(State state, int port, String detail) {
        this.state = state;
        this.port = port;
        this.detail = detail == null ? "" : detail;
    }

    static AdbSnapshot checking() { return new AdbSnapshot(State.CHECKING, 0, ""); }

    static AdbSnapshot error(String detail) { return new AdbSnapshot(State.ERROR, 0, detail); }

    static AdbSnapshot of(State state, int port) { return new AdbSnapshot(state, port, ""); }

    /**
     * On when adbd is running with a TCP port: the live one (service.adb.tcp.port), or the
     * persistent one (persist.adb.tcp.port) when the live one is unset, which is adbd's own rule.
     * A negative live port turns TCP off and must not fall back to a stale persistent one.
     */
    static AdbSnapshot read(String adbd, String livePort, String persistentPort) {
        return read(adbd, livePort, persistentPort, null);
    }

    /**
     * As above, and with the socket tables ({@code /proc/net/tcp} and {@code tcp6}; null if
     * unreadable) also requires something listening on the port. The properties alone can say
     * 5555 while adbd started before the port was set and listens over USB only.
     */
    static AdbSnapshot read(String adbd, String livePort, String persistentPort, String sockets) {
        String advertised = livePort == null || livePort.trim().isEmpty() ? persistentPort : livePort;
        int port = num(advertised);
        boolean on = "running".equals(adbd) && port > 0 && (sockets == null || listening(sockets, port));
        return on ? new AdbSnapshot(State.ON, port, "") : new AdbSnapshot(State.OFF, 0, "");
    }

    /** A LISTEN (st 0A) row for this local port in /proc/net/tcp-format text. */
    static boolean listening(String sockets, int port) {
        String suffix = String.format(java.util.Locale.US, ":%04X", port);
        for (String line : sockets.split("\n")) {
            String[] f = line.trim().split("\s+");
            if (f.length > 3 && f[1].toUpperCase(java.util.Locale.US).endsWith(suffix) && f[3].equals("0A")) return true;
        }
        return false;
    }

    private static int num(String value) {
        try { return Integer.parseInt(value == null ? "" : value.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
