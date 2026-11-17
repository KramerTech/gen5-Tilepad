package com.tilepad;

import com.tilepad.tweakcore.AmbientPolicy;
import java.io.StringReader;
import java.util.Properties;

/** Immutable launcher state for the Ambient Lighting tile and quick-control Sheet. */
final class AmbientSnapshot {
    enum State { CHECKING, OFF, ACTIVE, PAUSED, OWNER_YIELDED, NOT_FITTED, UNAVAILABLE }

    final State state;
    final AmbientPolicy.Mode mode;
    final int rgb;
    final int level;
    final String colorName;
    final String headline;
    final String detail;
    final String dayNight;
    final String equipment;

    private AmbientSnapshot(State state, AmbientPolicy.Mode mode, int rgb, int level,
                            String colorName, String headline, String detail, String dayNight, String equipment) {
        this.state = state;
        this.mode = mode;
        this.rgb = rgb & 0xffffff;
        this.level = level;
        this.colorName = colorName;
        this.headline = headline;
        this.detail = detail;
        this.dayNight = dayNight;
        this.equipment = equipment;
    }

    static AmbientSnapshot checking() {
        return new AmbientSnapshot(State.CHECKING, AmbientPolicy.Mode.OFF, 0x00B4FF, 7, "Cyan", "Checking…", "", "", "auto");
    }

    static AmbientSnapshot of(State state, AmbientPolicy.Mode mode, int rgb, int level,
                              String headline, String detail, String dayNight, String equipment) {
        return new AmbientSnapshot(state, mode, rgb, level, AmbientPolicy.colorName(rgb), headline, detail, dayNight, equipment);
    }

    static AmbientSnapshot parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return of(State.OFF, AmbientPolicy.Mode.OFF, 0x00B4FF, 7, "Owner", "Car default", "", "auto");
        }
        try {
            Properties p = new Properties();
            p.load(new StringReader(text));
            return fromProperties(p);
        } catch (Exception e) {
            return of(State.UNAVAILABLE, AmbientPolicy.Mode.OFF, 0x00B4FF, 0, "Unavailable", "Could not read ambient state", "", "auto");
        }
    }

    static AmbientSnapshot fromProperties(Properties p) {
        if (p == null) return checking();
        String st = p.getProperty("state", "off");
        State state;
        switch (st) {
            case "active": state = State.ACTIVE; break;
            case "paused": state = State.PAUSED; break;
            case "owner_yielded": state = State.OWNER_YIELDED; break;
            case "not_fitted": state = State.NOT_FITTED; break;
            case "unavailable": state = State.UNAVAILABLE; break;
            default: state = State.OFF; break;
        }
        AmbientPolicy.Mode mode = AmbientPolicy.Mode.of(p.getProperty("mode", "daynight"));
        if (state == State.OFF) mode = AmbientPolicy.Mode.OFF;
        int rgb = 0x00B4FF;
        try { rgb = AmbientPolicy.parseHex(p.getProperty("colorHex", "#00B4FF")); } catch (Exception ignored) { }
        int level = 7;
        try { level = Math.max(0, Math.min(10, Integer.parseInt(p.getProperty("level", "7")))); } catch (Exception ignored) { }
        String headline = p.getProperty("headline", state == State.OFF ? "Owner" : mode.label);
        String detail = p.getProperty("detail", state == State.OFF ? "Car default" : AmbientPolicy.colorName(rgb) + " · Lv " + level);
        return of(state, mode, rgb, level, headline, detail, p.getProperty("dayNight", ""), p.getProperty("equipment", "auto"));
    }

    String pill() {
        switch (state) {
            case CHECKING: return null;
            case ACTIVE: return "Active";
            case PAUSED: return "Paused";
            case OWNER_YIELDED: return "Owner";
            case NOT_FITTED: return "None";
            case UNAVAILABLE: return "Error";
            default: return "Off";
        }
    }

    PreconPresenter.Tone tone() {
        switch (state) {
            case ACTIVE: return PreconPresenter.Tone.GOOD;
            case PAUSED:
            case UNAVAILABLE: return PreconPresenter.Tone.WARN;
            default: return PreconPresenter.Tone.MUTED;
        }
    }

    String tileHeadline() {
        switch (state) {
            case CHECKING: return "Checking…";
            case PAUSED: return "Paused";
            case OFF: return "Owner";
            case OWNER_YIELDED: return "Owner";
            case NOT_FITTED: return "Not fitted";
            case UNAVAILABLE: return "Unavailable";
            default: return mode.label;
        }
    }

    String tileDetail() {
        switch (state) {
            case OFF: return "Car default";
            case PAUSED: return "In Drive · Park only";
            case ACTIVE:
                String prefix = mode == AmbientPolicy.Mode.DAY_NIGHT && !dayNight.isEmpty() ? dayNight + " · " : "";
                return prefix + colorName + " · Lv " + level;
            default: return detail;
        }
    }
}
