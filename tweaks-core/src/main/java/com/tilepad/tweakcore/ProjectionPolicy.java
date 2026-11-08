package com.tilepad.tweakcore;

/** Each available connection is opened once. Unknown observations never imply a disconnect. */
public final class ProjectionPolicy {
    public static final int CARPLAY = 1, ANDROID_AUTO = 2;
    private int seen;
    public ProjectionPolicy(int seen) { this.seen = seen & 3; }
    public static boolean cameraForeground(String packageName) {
        if (packageName == null) return false;
        String value = packageName.toLowerCase(java.util.Locale.US);
        return value.contains("rearcamera") || value.contains(".camera") || value.contains(".avm") || value.contains("surroundview");
    }
    public int seen() { return seen; }
    /** A new drive: a session that survived the car being off opens again. */
    public void newDrive() { seen = 0; }
    public int observe(Integer available) {
        if (available == null || available < 0 || available > 3) return 0;
        int newlyAvailable = available & ~seen; seen = available;
        return (newlyAvailable & CARPLAY) != 0 ? CARPLAY : (newlyAvailable & ANDROID_AUTO) != 0 ? ANDROID_AUTO : 0;
    }
}
