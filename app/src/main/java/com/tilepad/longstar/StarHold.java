package com.tilepad.longstar;

/**
 * What a ★ hold does about the car's own ★ action, the one Button settings assign (Home, for
 * example). The car runs that action about 20 ms after the release, after a hold too, so it would
 * cover Tilepad. When a hold qualifies:
 * - with ★ set to None, there's no action to run, so Tilepad opens at once (NOT_NEEDED);
 * - otherwise the car is asked to skip its action (IModeManager.disableKeyAction), and Tilepad opens
 *   at once (BLOCKED);
 * - if the action can't be read or the block doesn't take (CANT_BLOCK), Tilepad opens just after the
 *   release instead, after the car's own action.
 * A block is lifted 500 ms after the release, at the next press, or 2 minutes after it was made,
 * whichever comes first. The host's mark (star.blocked) is there while it's in place, so a daemon
 * started after one was stopped mid-hold lifts it; without the mark, nothing is lifted at start. A
 * short press is never touched.
 *
 * Pure logic: the host makes the mode service calls, runs the timer, keeps the mark and opens
 * Tilepad. onKey runs on the key reader's thread, the timer's tasks on the timer's.
 */
final class StarHold {
    /** What a qualifying hold did about the car's ★ action. */
    enum Block { BLOCKED, NOT_NEEDED, CANT_BLOCK }

    /**
     * None's action in Button settings for the ★ and for the custom key (the Sonata's): the
     * long-press broadcast, which the car sends only with None.
     */
    static final String NONE_ACTION = "com.mobis.intent.action.SWRC_CUSTOM_SHORTCUT",
        CUSTOM_NONE_ACTION = "com.mobis.intent.action.CUSTOM_SHORTCUT";
    static final long COOLDOWN_MS = 1500;
    /** Opening this long after the release keeps Tilepad on top of the car's own action. */
    static final long AFTER_RELEASE_MS = 250;
    /** A block is lifted this long after the release (the car acts about 20 ms after it). */
    static final long UNBLOCK_AFTER_RELEASE_MS = 500;
    /** And at the latest this long after it was made, in case the release is never seen. */
    static final long MAX_BLOCK_MS = 120000;

    /** A timer task that can be called off. */
    interface Task { void cancel(); }

    /** What the daemon does for a hold. */
    interface Host {
        /** ★'s action in Button settings (getKeyAction); throws when it can't be read. */
        String action() throws Exception;
        /** Asks the car to skip these ★ actions (disableKeyAction); throws when that didn't take. */
        void disable(String[] actions) throws Exception;
        /** Allows the car's ★ actions again (enableKeyAction): every block on ★, whoever made it. */
        void enable() throws Exception;
        /** Runs r after ms on the timer's thread. */
        Task after(long ms, Runnable r);
        /** Whether the mark of a block this daemon made is there. */
        boolean marked();
        void mark(boolean blocked);
        void open();
        long now();
        void log(String line);
    }

    private final Host host;
    private final HoldDetector detector;
    private final String noneAction;
    private Task pending;
    /** The hold in progress already opened Tilepad (its block took, or there was nothing to block). */
    private boolean openedThisPress;
    /** The car's ★ action is blocked by this daemon; blockSeq names the hold that blocked it. */
    private boolean blockActive;
    private int blockSeq;

    StarHold(Host host, long holdMs) { this(host, holdMs, NONE_ACTION); }

    /** noneAction: the held key's None action (NONE_ACTION for the ★, CUSTOM_NONE_ACTION for the custom key). */
    StarHold(Host host, long holdMs, String noneAction) {
        this.host = host;
        this.noneAction = noneAction;
        detector = new HoldDetector(holdMs, COOLDOWN_MS);
    }

    long holdMs() { return detector.holdMs(); }

    /** At the daemon's start: lifts a block that a daemon stopped mid-hold left, and only then. */
    synchronized void start() {
        if (!host.marked()) return;
        enable("start");
        host.mark(false);
    }

    /** A raw key value from the reader (1 down, 0 up, 2 autorepeat). */
    synchronized void onKey(int value) {
        int action = detector.onKey(value, host.now());
        if (action == HoldDetector.ARM) {
            cancelPending();
            // The car acts on this press's release, so a block still waiting out the last hold's
            // release delay must go now, or a quick short press after a hold would be swallowed.
            unblock(blockSeq, "next press");
            openedThisPress = false;
            final int generation = detector.generation();
            pending = host.after(detector.holdMs(), new Runnable() {
                @Override public void run() { onTimer(generation); }
            });
        } else if (action == HoldDetector.DISARM) {
            cancelPending();
        } else if (action == HoldDetector.HELD_RELEASE) {
            cancelPending();
            if (!openedThisPress) {
                // The block didn't take: open just after the car's own action instead.
                pending = host.after(AFTER_RELEASE_MS, new Runnable() {
                    @Override public void run() { host.open(); }
                });
            }
            if (blockActive) {
                final int seq = blockSeq;
                host.after(UNBLOCK_AFTER_RELEASE_MS, new Runnable() {
                    @Override public void run() { unblock(seq, "release"); }
                });
            }
            openedThisPress = false;
        }
    }

    /** The key device went away: the press is forgotten, and a block lifted. */
    synchronized void deviceLost() {
        cancelPending();
        detector.reset();
        openedThisPress = false;
        unblock(blockSeq, "device lost");
    }

    private void onTimer(int generation) {
        Block outcome;
        // One locked step from qualifying to recording the block, so a release that arrives
        // meanwhile waits and then sees it (and doesn't open Tilepad a second time).
        synchronized (this) {
            if (!detector.onTimer(generation, host.now())) return;
            outcome = block();
            openedThisPress = outcome != Block.CANT_BLOCK;
        }
        if (outcome != Block.CANT_BLOCK) host.open();
    }

    /** Blocks the car's ★ action, if it has one; the caller holds the lock. */
    private Block block() {
        String action;
        try {
            action = host.action();
        } catch (Exception e) {
            host.log("hold: couldn't read the car's star action, so Tilepad opens after the release: " + e);
            return Block.CANT_BLOCK;
        }
        if (action == null || action.isEmpty() || action.equals(noneAction)) {
            host.log("hold: the car's star action is None; nothing to block");
            return Block.NOT_NEEDED;
        }
        try {
            host.disable(new String[]{action, noneAction});
        } catch (Exception e) {
            host.log("hold: couldn't block the car's star action (" + action + "), so Tilepad opens after the release: " + e);
            return Block.CANT_BLOCK;
        }
        blockActive = true;
        final int seq = ++blockSeq;
        host.mark(true);
        host.after(MAX_BLOCK_MS, new Runnable() {
            @Override public void run() { unblock(seq, "2-minute limit"); }
        });
        host.log("hold: car's star action blocked (" + action + ")");
        return Block.BLOCKED;
    }

    /** Allows the car's ★ action again if hold seq's block is still the one in place. */
    private synchronized void unblock(int seq, String why) {
        if (!blockActive || seq != blockSeq) return;
        blockActive = false;
        enable(why);
        host.mark(false);
    }

    private void enable(String why) {
        try {
            host.enable();
            host.log("car's star action allowed again (" + why + ")");
        } catch (Exception e) {
            host.log("couldn't allow the car's star action again (" + why + "): " + e);
        }
    }

    private void cancelPending() {
        if (pending != null) {
            pending.cancel();
            pending = null;
        }
    }
}
