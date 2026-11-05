package com.tilepad;

/** One square on the grid. Core tiles are fixed features; shortcuts point at an activity. */
final class TileSpec {
    enum Kind {
        WIFI("core:wifi"), ADB("core:adb"), PRECONDITION("core:precondition"), TWEAKS("core:tweaks"), NEMESIS("core:nemesis"),
        CAR_HOME("core:car_home"), REBOOT("core:reboot"), ADD("core:add"), SHORTCUT(null);

        final String coreId;
        Kind(String coreId) { this.coreId = coreId; }
    }

    /** Default order for a fresh install; also the order missing core tiles are restored in. */
    static final Kind[] CORE = {Kind.WIFI, Kind.ADB, Kind.PRECONDITION, Kind.TWEAKS, Kind.NEMESIS, Kind.CAR_HOME, Kind.REBOOT, Kind.ADD};

    final String id;
    final Kind kind;
    /** Flattened short component name, shortcuts only. */
    final String component;
    /** Whether the activity declared MAIN + LAUNCHER when it was added (adds CATEGORY_LAUNCHER on open). */
    final boolean launcher;
    /** User-chosen name for a shortcut, or null to use the activity's own label. */
    final String label;

    private TileSpec(String id, Kind kind, String component, boolean launcher, String label) {
        this.id = id; this.kind = kind; this.component = component;
        this.launcher = launcher; this.label = label;
    }

    static TileSpec core(Kind kind) {
        if (kind == Kind.SHORTCUT) throw new IllegalArgumentException("Not a core tile");
        return new TileSpec(kind.coreId, kind, null, false, null);
    }

    static TileSpec shortcut(String component, boolean launcher, String label) {
        return new TileSpec("sc:" + component, Kind.SHORTCUT, component, launcher, emptyToNull(label));
    }

    boolean isCore() { return kind != Kind.SHORTCUT; }

    boolean isStatus() {
        return kind == Kind.WIFI || kind == Kind.ADB || kind == Kind.PRECONDITION || kind == Kind.TWEAKS;
    }

    TileSpec withLabel(String value) {
        return new TileSpec(id, kind, component, launcher, emptyToNull(value));
    }

    private static String emptyToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
