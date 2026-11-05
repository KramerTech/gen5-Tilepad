package com.tilepad.longstar;

/**
 * Pure long-press state machine for one evdev key. The caller feeds raw key values
 * (1 = down, 0 = up, 2 = kernel autorepeat) and arms a timer when told to. The timer
 * callback passes back the generation it was armed with, so a stale timer from an
 * earlier press can never fire.
 *
 * On this car the MICOM only emits 1 and 0 for its keys (TUNE and the steering-wheel star
 * alike); the "long press" Android logs is synthesized above the kernel, so the hold must
 * be timed here.
 */
public final class HoldDetector {
    public static final int NONE = 0;
    public static final int ARM = 1;
    public static final int DISARM = 2;

    private final long holdMs;
    private final long cooldownMs;
    private boolean down;
    private boolean firedThisPress;
    private long downAt;
    private long lastFire = Long.MIN_VALUE / 2;
    private int generation;

    public HoldDetector(long holdMs, long cooldownMs) {
        this.holdMs = holdMs;
        this.cooldownMs = cooldownMs;
    }

    public long holdMs() { return holdMs; }

    /** Current press generation; pass it to {@link #onTimer} when the timer fires. */
    public int generation() { return generation; }

    public int onKey(int value, long now) {
        if (value == 1) {
            if (down) return NONE;
            down = true;
            firedThisPress = false;
            downAt = now;
            generation++;
            return ARM;
        }
        if (value == 0) {
            if (!down) return NONE;  // release whose press we never saw (e.g. daemon restart)
            down = false;
            generation++;
            return DISARM;
        }
        return NONE;  // autorepeat: still held, nothing changes
    }

    /** Forget any press in progress, e.g. when the device disappears mid-hold. */
    public void reset() {
        down = false;
        generation++;
    }

    /** @return true exactly once per qualifying hold. */
    public boolean onTimer(int armedGeneration, long now) {
        if (!down || firedThisPress || armedGeneration != generation) return false;
        if (now - downAt < holdMs) return false;
        if (now - lastFire < cooldownMs) return false;
        firedThisPress = true;
        lastFire = now;
        return true;
    }
}
