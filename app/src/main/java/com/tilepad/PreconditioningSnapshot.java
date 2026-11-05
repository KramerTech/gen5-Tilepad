package com.tilepad;

import java.util.Locale;

/** Immutable UI state. A pending request and the vehicle indicator are independent. */
final class PreconditioningSnapshot {
    enum State { CHECKING, UNAVAILABLE, IDLE, WAITING, ACTIVE, STOPPING, ENDED, UNKNOWN, ERROR, WARM, TIMED_OUT }
    final State state;
    final boolean requested, canStart, canStop;
    final int indicator, failureRaw;
    final long elapsedMs, warmRemainingSeconds;
    final String detail;
    PreconditioningSnapshot(State state, boolean requested, boolean canStart, boolean canStop,
                            int indicator, int failureRaw, long elapsedMs, String detail) {
        this(state, requested, canStart, canStop, indicator, failureRaw, elapsedMs, detail, 0);
    }
    PreconditioningSnapshot(State state, boolean requested, boolean canStart, boolean canStop,
                            int indicator, int failureRaw, long elapsedMs, String detail, long warmRemainingSeconds) {
        this.warmRemainingSeconds = warmRemainingSeconds;
        this.state = state; this.requested = requested; this.canStart = canStart;
        this.canStop = canStop; this.indicator = indicator; this.failureRaw = failureRaw;
        this.elapsedMs = elapsedMs; this.detail = detail;
    }
    static PreconditioningSnapshot checking() {
        return new PreconditioningSnapshot(State.CHECKING, false, false, false, -1, -1, 0,
            "Checking vehicle services");
    }
    String title() {
        switch (state) {
            case CHECKING: return "Checking";
            case UNAVAILABLE: return "Unavailable";
            case IDLE: return indicator == 1 ? "Active · car controlled" : "Ready";
            case WAITING: return "Waiting for BMS";
            case ACTIVE: return "Active";
            case STOPPING: return "Stop requested";
            case ENDED: return "Session ended";
            case WARM: return "Battery warm";
            case TIMED_OUT: return "No activation";
            case UNKNOWN: return "Status unknown";
            default: return "Request error";
        }
    }
    String elapsed() {
        long seconds = Math.max(0, elapsedMs) / 1000;
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }
    String indicatorLabel() {
        return indicator == 1 ? "Car indicator: Active" : indicator == 0
            ? "Car indicator: Off" : "Car indicator: Unknown";
    }
}
