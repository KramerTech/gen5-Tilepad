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
        String advertised = livePort == null || livePort.trim().isEmpty() ? persistentPort : livePort;
        int port = num(advertised);
        return "running".equals(adbd) && port > 0 ? new AdbSnapshot(State.ON, port, "") : new AdbSnapshot(State.OFF, 0, "");
    }

    private static int num(String value) {
        try { return Integer.parseInt(value == null ? "" : value.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
