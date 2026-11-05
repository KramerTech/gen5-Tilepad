package com.tilepad;

/**
 * Maps the controller's snapshot onto the one-button tile and its popup. Only the car's
 * own indicator can produce "Active"; a request in flight is always "Waiting".
 */
final class PreconPresenter {
    enum Tone { MUTED, READY, BUSY, GOOD, WARN, BAD }
    enum Tap { START, STOP, STATUS }

    static Tap tap(PreconditioningSnapshot s) {
        if (s.state == PreconditioningSnapshot.State.WARM) return Tap.STATUS;
        if (s.canStart) return Tap.START;
        // "Click again after success to disable": only an Active session stops on a single tap.
        // While waiting, the tap opens the popup, whose Cancel button stops the request.
        if (s.state == PreconditioningSnapshot.State.ACTIVE && s.canStop) return Tap.STOP;
        return Tap.STATUS;
    }

    static String headline(PreconditioningSnapshot s) {
        switch (s.state) {
            case CHECKING: return "Checking";
            case UNAVAILABLE: return "Unavailable";
            case IDLE: case ENDED: return s.indicator == 1 ? "Car active" : "Off";
            case WAITING: return "Waiting";
            case WARM: return "Battery warm";
            case TIMED_OUT: return "No activation";
            case ACTIVE: return "Active";
            case STOPPING: return "Stopping";
            case UNKNOWN: return "Unknown";
            default: return "Error";
        }
    }

    static Tone tone(PreconditioningSnapshot s) {
        switch (s.state) {
            case CHECKING: case STOPPING: return Tone.BUSY;
            case WAITING: case WARM: case TIMED_OUT: return Tone.WARN;
            case ACTIVE: return Tone.GOOD;
            case IDLE: case ENDED: return s.indicator == 1 ? Tone.GOOD : s.canStart ? Tone.READY : Tone.MUTED;
            case UNAVAILABLE: return Tone.MUTED;
            case UNKNOWN: return Tone.WARN;
            default: return Tone.BAD;
        }
    }

    static boolean busy(PreconditioningSnapshot s) {
        return s.state == PreconditioningSnapshot.State.CHECKING
            || s.state == PreconditioningSnapshot.State.WAITING
            || s.state == PreconditioningSnapshot.State.STOPPING;
    }

    /** Small line under the headline. */
    /** The tile's detail row: elapsed time while a request runs, else what the state needs said. */
    static String detail(PreconditioningSnapshot s) {
        if (s.requested) return "Elapsed " + s.elapsed();
        switch (s.state) {
            case IDLE: case ENDED: case TIMED_OUT: return s.indicator == 1 ? "Controlled by the car" : "";
            case CHECKING: return "Vehicle services";
            default: return "";
        }
    }

    /** The tile's bottom hint: what a tap does. */
    static String hint(PreconditioningSnapshot s) {
        switch (s.state) {
            case CHECKING: return "";
            case IDLE: case ENDED: case TIMED_OUT: return !s.requested && s.indicator != 1 && s.canStart ? "Tap to start" : "Tap for details";
            default: return "Tap for details";
        }
    }

    /**
     * The car's own conditioning indicator, for the tile's status pill; null unless it's on.
     * An off indicator gets no pill: "Car off" read as the car itself being off.
     */
    static String indicator(PreconditioningSnapshot s) {
        return s.indicator == 1 ? "Conditioning" : null;
    }

    /** "Waiting for BMS — activation may…" under a "Waiting for BMS" title reads "Activation may…". */
    static String withoutPrefix(String text, String prefix) {
        if (text == null || !text.startsWith(prefix) || text.length() == prefix.length()) return text;
        String rest = text.substring(prefix.length());
        return Character.toUpperCase(rest.charAt(0)) + rest.substring(1);
    }

    private PreconPresenter() { }
}
